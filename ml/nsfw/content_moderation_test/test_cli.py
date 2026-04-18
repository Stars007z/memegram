"""
CLI-скрипт для тестирования модели на отдельных изображениях без веб-сервера.
Использование:
    python test_cli.py path/to/image.jpg
    python test_cli.py path/to/image.jpg --threshold 0.3 --action blur --output result.png
"""

import argparse
import os
import sys
from pathlib import Path

# Путь к модели
MODEL_DIR = Path(__file__).parent.parent / "model_to_prod" / "ViT-L14_336px_model_Andrey_v61_prod"
MODEL_PATH_FP16 = MODEL_DIR / "ViT-L14_336px_model_Andrey_v61_v16_prod_fp16.onnx"
MODEL_PATH_FP32 = MODEL_DIR / "ViT-L14_336px_model_Andrey_v61_v16_prod.onnx"


def main():
    parser = argparse.ArgumentParser(description="Test content moderation model on an image")
    parser.add_argument("image", help="Path to image file")
    parser.add_argument("--threshold", type=float, default=0.5, help="Confidence threshold (0.0-1.0)")
    parser.add_argument("--action", choices=["blur", "block"], default="blur", help="Action for unsafe content")
    parser.add_argument("--output", "-o", help="Output path for blurred image (only for blur action)")
    parser.add_argument("--model", help="Path to ONNX model (auto-detected if omitted)")
    args = parser.parse_args()

    # Проверяем изображение
    if not os.path.exists(args.image):
        print(f"[ERROR] Image not found: {args.image}")
        sys.exit(1)

    # Определяем модель
    model_path = args.model
    if not model_path:
        if MODEL_PATH_FP32.exists():
            model_path = str(MODEL_PATH_FP32)
        elif MODEL_PATH_FP16.exists():
            model_path = str(MODEL_PATH_FP16)
        else:
            print(f"[ERROR] Model not found. Tried:")
            print(f"  {MODEL_PATH_FP16}")
            print(f"  {MODEL_PATH_FP32}")
            sys.exit(1)

    from inference import ContentModerator

    print(f"Loading model...")
    moderator = ContentModerator(model_path, threshold=args.threshold)

    # Читаем изображение
    with open(args.image, "rb") as f:
        image_bytes = f.read()

    # Классификация
    result = moderator.classify_image_bytes(image_bytes)

    # Вывод результата
    print()
    print("=" * 50)
    print(f"  File:       {args.image}")
    print(f"  Class:      {result['predicted_class']} ({result['predicted_class_ru']})")
    print(f"  Confidence: {result['confidence'] * 100:.1f}%")
    print(f"  Unsafe:     {'YES' if result['is_unsafe'] else 'NO'}")
    print(f"  Threshold:  {result['threshold']}")
    print("=" * 50)
    print()
    print("  All scores:")
    for label, score in result['all_scores'].items():
        bar = '#' * int(score * 40)
        print(f"    {label:12s}  {score*100:6.2f}%  |{bar}")
    print()

    # Действие
    if result['is_unsafe']:
        if args.action == "blur":
            output_path = args.output or f"blurred_{os.path.basename(args.image)}"
            blurred = moderator.blur_image(image_bytes)
            with open(output_path, "wb") as f:
                f.write(blurred)
            print(f"  [BLURRED] Saved to: {output_path}")
        else:
            print(f"  [BLOCKED] Image would be blocked in production.")
    else:
        print(f"  [ALLOWED] Image is safe.")


if __name__ == "__main__":
    main()
