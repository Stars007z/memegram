"""
Бенчмарк: симулирует startup сервера (warmup) + 2 последовательных запроса
на тестовой картинке. Подтверждает что warmup устраняет 19с outlier.

Использование:
    python benchmark_warmup.py
    python benchmark_warmup.py path/to/image.jpg
"""

import sys
import time
from pathlib import Path

import numpy as np
import cv2

HERE = Path(__file__).parent
MODEL_PATH = HERE.parent / "model_to_prod" / "ViT-L14_336px_model_Andrey_v61_prod" / "ViT-L14_336px_model_Andrey_v61_v16_prod.onnx"
DEFAULT_IMAGE = HERE / "test_images" / "test_swastika.webp"


def main():
    image_path = Path(sys.argv[1]) if len(sys.argv) > 1 else DEFAULT_IMAGE
    if not image_path.exists():
        print(f"[ERROR] Image not found: {image_path}")
        sys.exit(1)

    from inference import ContentModerator, OWL_LABELS

    # === 1. Загрузка ViT ===
    print("=" * 60)
    print("[1] Загрузка ViT модели...")
    t = time.time()
    moderator = ContentModerator(str(MODEL_PATH), threshold=1.8)
    print(f"    OK ({time.time()-t:.2f}s)")

    # === 2. Warmup (как в app.py startup) ===
    print()
    print("[2] Warmup всех auxiliary моделей...")
    warmup_start = time.time()

    dummy = np.random.randint(0, 255, (320, 320, 3), dtype=np.uint8)

    print("    [Warmup] NudeNet...")
    t = time.time()
    _ = moderator.nude_detector.detect(dummy)
    print(f"    [Warmup] NudeNet OK ({(time.time()-t)*1000:.0f} ms)")

    print("    [Warmup] Anime censor...")
    t = time.time()
    if moderator._ensure_anime_detector():
        from imgutils.detect import detect_censors
        from PIL import Image
        _ = detect_censors(Image.fromarray(cv2.cvtColor(dummy, cv2.COLOR_BGR2RGB)))
    print(f"    [Warmup] Anime censor OK ({(time.time()-t)*1000:.0f} ms)")

    print("    [Warmup] OWL-ViTv2 (тяжёлый, ~15-30с)...")
    t = time.time()
    pipe = moderator.owl_pipeline
    if pipe is not None:
        from PIL import Image
        _ = pipe(Image.fromarray(cv2.cvtColor(dummy, cv2.COLOR_BGR2RGB)),
                 candidate_labels=OWL_LABELS)
    print(f"    [Warmup] OWL OK ({(time.time()-t)*1000:.0f} ms)")

    print(f"    Warmup total: {time.time()-warmup_start:.1f}s")

    # === 3. Реальные запросы ===
    print()
    print(f"[3] Тестовая картинка: {image_path.name}")
    image_bytes = image_path.read_bytes()

    for i in range(1, 4):
        print()
        print(f"--- Запрос #{i} ---")
        t = time.time()
        result = moderator.classify_image_bytes(image_bytes)
        cls_time = time.time() - t

        t = time.time()
        partial_bytes, patch_results = moderator.partial_blur_image(
            image_bytes,
            predicted_class=result["predicted_class"],
            is_unsafe=result["is_unsafe"],
            all_scores=result["all_scores"],
        )
        blur_time = time.time() - t

        total = cls_time + blur_time
        print(f"  classify:     {cls_time*1000:7.0f} ms")
        print(f"  partial_blur: {blur_time*1000:7.0f} ms")
        print(f"  TOTAL:        {total*1000:7.0f} ms  ({total:.2f}s)")
        print(f"  predicted:    {result['predicted_class']} ({result['confidence']*100:.1f}%)")
        print(f"  blurred:      {patch_results.get('blurred')}  regions={len(patch_results.get('regions', []))}")

    print()
    print("=" * 60)
    print("Если запросы #2 и #3 быстрые (~3-5s), warmup работает правильно.")


if __name__ == "__main__":
    main()
