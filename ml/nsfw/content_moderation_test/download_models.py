"""
Скрипт пред-загрузки всех моделей в локальную папку ./models/.

После запуска этого скрипта приложение работает БЕЗ интернета —
все модели подгружаются из ./models/.

Структура:
  ./models/hf/           — HuggingFace cache (OWL-ViTv2, dghs-imgutils anime detector)
  ./models/nudenet/      — NudeNet ONNX веса
  ./model_to_prod/...    — ViT-L14 (уже на месте)

Запуск:
  python download_models.py
"""
import os
import sys

# Устанавливаем кэш-пути ДО импорта чего-либо что использует HF
_MODULE_DIR = os.path.dirname(os.path.abspath(__file__))
MODELS_DIR = os.path.join(_MODULE_DIR, "models")

os.environ["HF_HOME"] = os.path.join(MODELS_DIR, "hf")
os.environ["HF_HUB_CACHE"] = os.path.join(MODELS_DIR, "hf")
os.environ["HF_HUB_DISABLE_SYMLINKS_WARNING"] = "1"
os.environ["NUDENET_CACHE_DIR"] = os.path.join(MODELS_DIR, "nudenet")

os.makedirs(os.environ["HF_HOME"], exist_ok=True)
os.makedirs(os.environ["NUDENET_CACHE_DIR"], exist_ok=True)


def download_owl():
    """Скачивает OWL-ViTv2-base в ./models/hf/."""
    print("\n[1/3] OWL-ViTv2-base (zero-shot detector для свастики/hate symbols)")
    print("      Размер: ~600 MB")
    try:
        from transformers import pipeline
        pipe = pipeline(
            "zero-shot-object-detection",
            model="google/owlv2-base-patch16",
        )
        # Прогоняем dummy inference чтобы убедиться что веса загружены
        from PIL import Image
        import numpy as np
        dummy = Image.fromarray(np.zeros((64, 64, 3), dtype=np.uint8))
        _ = pipe(dummy, candidate_labels=["a swastika"])
        print("      OK")
    except Exception as e:
        print(f"      FAIL: {e}")
        return False
    return True


def download_nudenet():
    """Скачивает NudeNet ONNX модель."""
    print("\n[2/3] NudeNet (детектор обнажённых частей тела на фото)")
    print("      Размер: ~140 MB")
    try:
        from nudenet import NudeDetector
        detector = NudeDetector()
        # NudeDetector скачивает модель в свой кэш при инициализации
        print("      OK")
    except Exception as e:
        print(f"      FAIL: {e}")
        return False
    return True


def download_anime_censor():
    """Скачивает dghs-imgutils anime censor detector."""
    print("\n[3/3] Anime censor detector (dghs-imgutils, для аниме nudity)")
    print("      Размер: ~30 MB")
    try:
        from imgutils.detect import detect_censors
        from PIL import Image
        import numpy as np
        # Triggers download
        dummy = Image.fromarray(np.zeros((64, 64, 3), dtype=np.uint8))
        _ = detect_censors(dummy)
        print("      OK")
    except Exception as e:
        print(f"      FAIL: {e}")
        return False
    return True


def show_summary():
    """Показывает итоговый размер ./models/."""
    total = 0
    for root, _dirs, files in os.walk(MODELS_DIR):
        for f in files:
            try:
                total += os.path.getsize(os.path.join(root, f))
            except OSError:
                pass
    print(f"\n=== Готово ===")
    print(f"Папка ./models/ : {total / (1024*1024):.1f} MB")
    print(f"Путь: {MODELS_DIR}")


if __name__ == "__main__":
    print(f"Загружаю модели в: {MODELS_DIR}")
    results = []
    results.append(("OWL-ViTv2",      download_owl()))
    results.append(("NudeNet",        download_nudenet()))
    results.append(("Anime censor",   download_anime_censor()))

    print("\n=== Результаты ===")
    for name, ok in results:
        status = "OK" if ok else "FAIL"
        print(f"  [{status}] {name}")

    show_summary()
    sys.exit(0 if all(ok for _, ok in results) else 1)
