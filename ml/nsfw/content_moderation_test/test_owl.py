"""
Стендалон-тест OWL-ViTv2 на swastika-изображении.
Запуск: python test_owl.py [путь_к_изображению]
"""

import sys
import time
from pathlib import Path

DEFAULT_IMAGE = Path(__file__).parent / "test_images" / "test_swastika.webp"

img_path = Path(sys.argv[1]) if len(sys.argv) > 1 else DEFAULT_IMAGE

if not img_path.exists():
    print(f"[ERROR] Файл не найден: {img_path}")
    sys.exit(1)

print(f"[Test] Изображение: {img_path}")
print("[Test] Загружаю OWL-ViTv2 (первый запуск загрузит ~600МБ)...")
t0 = time.time()

from transformers import pipeline
from PIL import Image

pipe = pipeline(
    "zero-shot-object-detection",
    model="google/owlv2-base-patch16-ensemble",
)
print(f"[Test] Модель загружена за {time.time()-t0:.1f}с")

img = Image.open(img_path).convert("RGB")
print(f"[Test] Размер изображения: {img.size}")

LABELS = [
    "a swastika",
    "a swastika symbol",
    "a nazi flag",
    "a nazi armband",
    "SS runes",
    "a red armband with black symbol",
    "a hate symbol",
    "a black cross-shaped symbol",
]

print(f"[Test] Запуск инференса с метками: {LABELS}")
t0 = time.time()
results = pipe(img, candidate_labels=LABELS)
print(f"[Test] Инференс за {time.time()-t0:.1f}с")
print(f"[Test] Всего детекций: {len(results)}")

if not results:
    print("[Test] OWL ничего не нашёл вообще")
else:
    sorted_r = sorted(results, key=lambda r: r.get("score", 0), reverse=True)
    print("\n[Test] Все детекции (отсортированы по score):")
    for r in sorted_r:
        score = r.get("score", 0)
        label = r.get("label", "?")
        box = r.get("box", {})
        marker = "  Y" if score >= 0.10 else "  -"
        print(f"{marker} {label:35s} score={score:.4f}  "
              f"box=[{int(box.get('xmin',0))},{int(box.get('ymin',0))},"
              f"{int(box.get('xmax',0))},{int(box.get('ymax',0))}]")

    above_10 = [r for r in results if r.get("score", 0) >= 0.10]
    print(f"\n[Test] Выше порога 0.10: {len(above_10)}")
    print(f"[Test] Выше порога 0.05: {len([r for r in results if r.get('score', 0) >= 0.05])}")
