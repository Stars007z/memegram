#!/usr/bin/env python3
"""
Export facebook/nllb-200-distilled-600M to ONNX format for on-device translation.

Dependencies:
    pip install optimum[exporters] onnxruntime sentencepiece protobuf

Produces a single model package containing:
  - encoder_model.onnx      (INT8 quantized by default)
  - decoder_model.onnx      (INT8 quantized by default)
  - tokenizer.json           (vocab + piece_scores for Kotlin Viterbi tokenizer)
  - config.json              (model metadata + special token IDs)

Usage:
    python export_nllb.py                        # export with INT8 quantization
    python export_nllb.py --no-quantize          # keep FP32 (larger, ~1.2GB)
    python export_nllb.py --output ./my_models   # custom output directory

Output structure:
    exported_models/
    └── nllb-200-distilled-600M/
        ├── encoder_model.onnx   (~150MB INT8)
        ├── decoder_model.onnx   (~150MB INT8)
        ├── tokenizer.json       (~8MB)
        └── config.json          (<1KB)

One model for ALL 200 language pairs. No pivoting needed.
"""

import argparse
import json
import shutil
from pathlib import Path

MODEL_NAME = "facebook/nllb-200-distilled-600M"


def export_nllb(output_dir: Path, quantize: bool = True):
    model_dir = output_dir / "nllb-200-distilled-600M"
    onnx_tmp = model_dir / "_tmp_onnx"

    print(f"\n{'='*60}")
    print(f"Exporting {MODEL_NAME}")
    print(f"{'='*60}")

    # ── 1. Export to ONNX via optimum ──────────────────────────
    #
    # optimum handles the encoder/decoder split, dynamic axes,
    # and tracing correctly for M2M100/NLLB models — avoiding
    # the torch.onnx.export device meta bug in PyTorch 2.x.
    #
    print("  Exporting to ONNX via optimum (this downloads + converts)...")
    from optimum.exporters.onnx import main_export

    main_export(
        model_name_or_path=MODEL_NAME,
        output=onnx_tmp,
        task="text2text-generation",
        opset=14,
        device="cpu",
        no_post_process=True,
    )

    # optimum produces: encoder_model.onnx, decoder_model.onnx,
    # decoder_model_merged.onnx (with past_key_values), plus config/tokenizer files.
    # We only need encoder_model.onnx and decoder_model.onnx (no KV-cache for mobile).

    encoder_src = onnx_tmp / "encoder_model.onnx"
    decoder_src = onnx_tmp / "decoder_model.onnx"

    if not encoder_src.exists() or not decoder_src.exists():
        # List what was actually produced for debugging
        produced = [f.name for f in onnx_tmp.iterdir()] if onnx_tmp.exists() else []
        raise FileNotFoundError(
            f"Expected encoder_model.onnx and decoder_model.onnx in {onnx_tmp}. "
            f"Found: {produced}"
        )

    enc_size = encoder_src.stat().st_size / (1024 * 1024)
    dec_size = decoder_src.stat().st_size / (1024 * 1024)
    print(f"    encoder_model.onnx: {enc_size:.1f}MB (FP32)")
    print(f"    decoder_model.onnx: {dec_size:.1f}MB (FP32)")

    # ── 2. Quantize to INT8 ────────────────────────────────────
    model_dir.mkdir(parents=True, exist_ok=True)

    if quantize:
        from onnxruntime.quantization import quantize_dynamic, QuantType

        print("  Quantizing to INT8...")
        for onnx_name, src_path in [
            ("encoder_model.onnx", encoder_src),
            ("decoder_model.onnx", decoder_src),
        ]:
            int8_path = model_dir / onnx_name
            quantize_dynamic(
                str(src_path),
                str(int8_path),
                weight_type=QuantType.QInt8,
            )
            int8_sz = int8_path.stat().st_size / (1024 * 1024)
            fp32_sz = src_path.stat().st_size / (1024 * 1024)
            print(f"    {onnx_name}: {fp32_sz:.1f}MB -> {int8_sz:.1f}MB")
    else:
        print("  Copying FP32 models (no quantization)...")
        shutil.copy2(str(encoder_src), str(model_dir / "encoder_model.onnx"))
        shutil.copy2(str(decoder_src), str(model_dir / "decoder_model.onnx"))

    # Clean up tmp dir
    shutil.rmtree(str(onnx_tmp), ignore_errors=True)

    # ── 3. Save tokenizer.json ─────────────────────────────────
    #
    # NLLB uses SentencePiece BPE. We extract piece→score from the
    # .spm model and the full vocab→id mapping from the HF tokenizer.
    # Our Kotlin Viterbi tokenizer uses these for on-device encoding.
    #
    print("  Saving tokenizer...")
    from sentencepiece import sentencepiece_model_pb2
    from huggingface_hub import hf_hub_download
    from transformers import AutoTokenizer, AutoConfig

    tokenizer = AutoTokenizer.from_pretrained(MODEL_NAME)
    config = AutoConfig.from_pretrained(MODEL_NAME)

    # Get the SentencePiece model file
    spm_path = Path(hf_hub_download(MODEL_NAME, "sentencepiece.bpe.model"))

    spm_proto = sentencepiece_model_pb2.ModelProto()
    spm_proto.ParseFromString(spm_path.read_bytes())

    # Extract piece → score for NORMAL pieces
    piece_scores = {}
    max_piece_len = 0
    for p in spm_proto.pieces:
        if p.type == 1:  # NORMAL
            piece_scores[p.piece] = round(p.score, 6)
            max_piece_len = max(max_piece_len, len(p.piece))

    # Full vocabulary (includes language tokens)
    vocab = tokenizer.get_vocab()

    # Language token IDs (FLORES-200 codes → token IDs)
    lang_tokens = {}
    for token, tid in vocab.items():
        # NLLB language tokens look like: eng_Latn, rus_Cyrl, etc.
        if "_" in token and len(token) >= 7 and token[0].islower() and tid >= 256000:
            lang_tokens[token] = tid

    tokenizer_data = {
        "model_type": "nllb_sentencepiece_bpe",
        "vocab": vocab,
        "piece_scores": piece_scores,
        "max_piece_length": max_piece_len,
        "lang_tokens": lang_tokens,
    }

    tokenizer_path = model_dir / "tokenizer.json"
    with open(tokenizer_path, "w", encoding="utf-8") as f:
        json.dump(tokenizer_data, f, ensure_ascii=False)

    tok_size = tokenizer_path.stat().st_size / (1024 * 1024)
    print(f"    vocab: {len(vocab)} tokens, pieces: {len(piece_scores)}, "
          f"lang_tokens: {len(lang_tokens)}, max_len: {max_piece_len}")
    print(f"    tokenizer.json: {tok_size:.1f}MB")

    # ── 4. Save config.json ────────────────────────────────────
    model_config = {
        "model_name": MODEL_NAME,
        "vocab_size": config.vocab_size,
        "max_length": getattr(config, "max_length", 200),
        "decoder_start_token_id": config.decoder_start_token_id,
        "eos_token_id": config.eos_token_id,
        "pad_token_id": config.pad_token_id,
        "bos_token_id": getattr(config, "bos_token_id", 0),
    }
    with open(model_dir / "config.json", "w") as f:
        json.dump(model_config, f, indent=2)

    # ── 5. Summary ─────────────────────────────────────────────
    total_size = sum(
        f.stat().st_size for f in model_dir.iterdir() if f.is_file()
    ) / (1024 * 1024)
    print(f"\n  Total package size: {total_size:.1f}MB")
    print(f"  Output: {model_dir}")
    print(f"  Languages supported: {len(lang_tokens)}")


def main():
    parser = argparse.ArgumentParser(
        description="Export NLLB-200-distilled-600M to ONNX for on-device translation"
    )
    parser.add_argument(
        "--output", type=str, default="./exported_models",
        help="Output directory (default: ./exported_models)"
    )
    parser.add_argument(
        "--no-quantize", action="store_true",
        help="Skip INT8 quantization (keep FP32, ~1.2GB total)"
    )
    args = parser.parse_args()

    output_dir = Path(args.output)
    output_dir.mkdir(parents=True, exist_ok=True)

    print(f"Exporting NLLB-200-distilled-600M to {output_dir}")
    print(f"Dependencies: optimum[exporters], onnxruntime, sentencepiece, protobuf")

    try:
        export_nllb(output_dir, quantize=not args.no_quantize)
    except Exception as e:
        print(f"ERROR: {e}")
        import traceback
        traceback.print_exc()
        return 1

    print(f"\nDone! Model exported to {output_dir}/nllb-200-distilled-600M/")
    print(f"\nNext steps:")
    print(f"  1. Run: ./gradlew pushModelsToDevice")
    print(f"  2. Or manually: adb push exported_models/nllb-200-distilled-600M/ /sdcard/memegram/models/")
    return 0


if __name__ == "__main__":
    exit(main())
