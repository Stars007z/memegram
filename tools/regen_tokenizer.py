#!/usr/bin/env python3
"""Regenerate tokenizer.json for already-exported models using Viterbi format."""
import sys
sys.stdout.reconfigure(encoding='utf-8')

import json
from pathlib import Path
from sentencepiece import sentencepiece_model_pb2
from transformers import MarianTokenizer

PAIRS = ["en-ru", "ru-en", "en-de", "de-en"]
OUTPUT_DIR = Path("exported_models")

for pair in PAIRS:
    src, tgt = pair.split("-")
    model_name = f"Helsinki-NLP/opus-mt-{src}-{tgt}"
    pair_dir = OUTPUT_DIR / f"opus-mt-{src}-{tgt}"
    
    print(f"Processing {model_name}...")
    
    # Load HF tokenizer for combined vocab
    tok = MarianTokenizer.from_pretrained(model_name)
    vocab = tok.get_vocab()
    
    # Load source.spm for piece scores
    from huggingface_hub import hf_hub_download
    spm_path = Path(hf_hub_download(model_name, "source.spm"))
    
    spm_proto = sentencepiece_model_pb2.ModelProto()
    spm_proto.ParseFromString(spm_path.read_bytes())
    
    piece_scores = {}
    max_piece_len = 0
    for p in spm_proto.pieces:
        if p.type == 1:  # NORMAL
            piece_scores[p.piece] = round(p.score, 6)
            max_piece_len = max(max_piece_len, len(p.piece))
    
    tokenizer_data = {
        "model_type": "sentencepiece_bpe_viterbi",
        "vocab": vocab,
        "piece_scores": piece_scores,
        "max_piece_length": max_piece_len,
    }
    
    out_path = pair_dir / "tokenizer.json"
    with open(out_path, "w", encoding="utf-8") as f:
        json.dump(tokenizer_data, f, ensure_ascii=False)
    
    size_mb = out_path.stat().st_size / (1024 * 1024)
    print(f"  vocab: {len(vocab)}, pieces: {len(piece_scores)}, max_len: {max_piece_len}, file: {size_mb:.1f}MB")
    
    # Clean up old SPM/vocab files that are no longer needed
    for old_file in ["source.spm", "target.spm", "vocab.json", 
                     "special_tokens_map.json", "tokenizer_config.json"]:
        old_path = pair_dir / old_file
        if old_path.exists():
            old_path.unlink()
            print(f"  removed {old_file}")

print("\nDone!")
