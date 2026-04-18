"""
FastAPI сервер для тестирования модели контент-модерации.
Позволяет загружать изображения, классифицировать их и получать
версию с partial blur (блюр только unsafe-областей через скользящее окно + тепловую карту).
"""

import os
import sys

# UTF-8 stdout/stderr на Windows (cp1251 крашит print со стрелками)
try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    sys.stderr.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass

import base64
import time
from pathlib import Path
from typing import Optional

from fastapi import FastAPI, File, UploadFile, Request, Form, HTTPException
from fastapi.responses import HTMLResponse, JSONResponse
from fastapi.staticfiles import StaticFiles
from fastapi.templating import Jinja2Templates

from inference import ContentModerator

# --- Конфигурация ---

# Путь к модели (FP16 — быстрее, меньше памяти)
MODEL_DIR = Path(__file__).parent.parent / "model_to_prod" / "ViT-L14_336px_model_Andrey_v61_prod"
MODEL_PATH_FP16 = MODEL_DIR / "ViT-L14_336px_model_Andrey_v61_v16_prod_fp16.onnx"
MODEL_PATH_FP32 = MODEL_DIR / "ViT-L14_336px_model_Andrey_v61_v16_prod.onnx"

# Выбираем FP32 (FP16 может быть несовместима с новыми версиями onnxruntime)
if MODEL_PATH_FP32.exists():
    MODEL_PATH = str(MODEL_PATH_FP32)
elif MODEL_PATH_FP16.exists():
    MODEL_PATH = str(MODEL_PATH_FP16)
else:
    print(f"[ERROR] Модель не найдена ни по одному из путей:")
    print(f"  FP16: {MODEL_PATH_FP16}")
    print(f"  FP32: {MODEL_PATH_FP32}")
    sys.exit(1)

# Порог — множитель от базовой вероятности (1.8 = блок при >= 30%)
DEFAULT_THRESHOLD = 1.8

# --- Инициализация ---

app = FastAPI(
    title="Content Moderation Tester",
    description="Тестирование модели модерации контента (partial blur)",
    version="1.0.0",
)

templates = Jinja2Templates(directory=str(Path(__file__).parent / "templates"))
app.mount("/static", StaticFiles(directory=str(Path(__file__).parent / "static")), name="static")

# Загружаем модель при старте
moderator: Optional[ContentModerator] = None


@app.on_event("startup")
async def startup():
    global moderator
    print(f"[Server] Загрузка модели: {MODEL_PATH}")
    moderator = ContentModerator(MODEL_PATH, threshold=DEFAULT_THRESHOLD)

    # === Warmup всех моделей чтобы первый запрос пользователя был быстрым ===
    # Без этого NudeNet (~1с) и OWL (~15с в RAM) грузятся при первом запросе.
    print("[Server] Warmup моделей (~30-60с при первом запуске)...")
    warmup_start = time.time()
    try:
        import numpy as np
        import cv2
        # Маленькая dummy-картинка с шумом, чтобы модели реально что-то посчитали
        dummy = np.random.randint(0, 255, (320, 320, 3), dtype=np.uint8)
        _, dummy_bytes = cv2.imencode(".jpg", dummy)
        dummy_bytes = dummy_bytes.tobytes()

        # 1. NudeNet — загружаем ONNX в память
        print("[Warmup] NudeNet...")
        t = time.time()
        _ = moderator.nude_detector.detect(dummy)
        print(f"[Warmup] NudeNet OK ({(time.time()-t)*1000:.0f} ms)")

        # 2. Anime censor detector
        print("[Warmup] Anime censor...")
        t = time.time()
        if moderator._ensure_anime_detector():
            from imgutils.detect import detect_censors
            from PIL import Image
            _ = detect_censors(Image.fromarray(cv2.cvtColor(dummy, cv2.COLOR_BGR2RGB)))
        print(f"[Warmup] Anime censor OK ({(time.time()-t)*1000:.0f} ms)")

        # 3. OWL-ViTv2 — самая тяжёлая загрузка (~15с в RAM)
        print("[Warmup] OWL-ViTv2 (это займёт ~15-30с)...")
        t = time.time()
        from inference import OWL_LABELS
        pipe = moderator.owl_pipeline
        if pipe is not None:
            from PIL import Image
            _ = pipe(Image.fromarray(cv2.cvtColor(dummy, cv2.COLOR_BGR2RGB)),
                     candidate_labels=OWL_LABELS)
        print(f"[Warmup] OWL OK ({(time.time()-t)*1000:.0f} ms)")

    except Exception as e:
        print(f"[Warmup] Ошибка: {e}")

    total = time.time() - warmup_start
    print(f"[Server] Warmup завершён за {total:.1f}с — все модели в памяти")
    print("[Server] Сервер готов к работе!")


# --- Роуты ---


@app.get("/", response_class=HTMLResponse)
async def index(request: Request):
    """Главная страница с формой загрузки."""
    return templates.TemplateResponse(request=request, name="index.html")


@app.post("/api/moderate")
async def moderate_image(
    file: UploadFile = File(...),
    threshold: float = Form(DEFAULT_THRESHOLD),
):
    """
    API для модерации изображения.
    Всегда использует partial blur (скользящее окно + тепловая карта).

    Args:
        file: загружаемый файл изображения
        threshold: множитель порога (1.8 = unsafe при >= 30%)

    Returns:
        JSON с результатами классификации и обработанным изображением
    """
    if moderator is None:
        raise HTTPException(status_code=503, detail="Модель не загружена")

    # Валидация
    if not file.content_type or not file.content_type.startswith("image/"):
        raise HTTPException(status_code=400, detail="Файл должен быть изображением")

    if not (1.0 <= threshold <= 6.0):
        raise HTTPException(status_code=400, detail="Множитель должен быть от 1.0 до 6.0")

    # Читаем файл
    image_bytes = await file.read()
    if len(image_bytes) == 0:
        raise HTTPException(status_code=400, detail="Файл пустой")

    # Устанавливаем порог
    moderator.threshold = threshold

    # Классификация (целое изображение)
    start_time = time.time()
    result = moderator.classify_image_bytes(image_bytes)

    # Partial blur (NudeNet + sliding window для подозрительных не-nudity классов)
    partial_bytes, patch_results = moderator.partial_blur_image(
        image_bytes,
        predicted_class=result["predicted_class"],
        is_unsafe=result["is_unsafe"],
        all_scores=result["all_scores"],
    )

    inference_time = round((time.time() - start_time) * 1000, 1)  # мс

    result["inference_time_ms"] = inference_time
    result["filename"] = file.filename

    # Оригинальное изображение в base64
    original_b64 = base64.b64encode(image_bytes).decode("utf-8")
    content_type = file.content_type or "image/jpeg"
    result["original_image"] = f"data:{content_type};base64,{original_b64}"

    # Partial blur результат
    partial_b64 = base64.b64encode(partial_bytes).decode("utf-8")
    result["processed_image"] = f"data:image/png;base64,{partial_b64}"
    result["patch_results"] = patch_results

    # Если heatmap нашёл unsafe-области — помечаем
    if patch_results.get("blurred"):
        result["is_unsafe"] = True
        result["action_taken"] = "partial_blur"
    else:
        result["action_taken"] = "allowed"

    return JSONResponse(content=result)


@app.post("/api/moderate/batch")
async def moderate_batch(
    files: list[UploadFile] = File(...),
    threshold: float = Form(DEFAULT_THRESHOLD),
):
    """
    Пакетная модерация нескольких изображений.
    Возвращает классификацию без partial blur (для скорости).
    """
    if moderator is None:
        raise HTTPException(status_code=503, detail="Модель не загружена")

    moderator.threshold = threshold
    results = []

    for file in files:
        if not file.content_type or not file.content_type.startswith("image/"):
            results.append({"filename": file.filename, "error": "Не изображение"})
            continue

        image_bytes = await file.read()
        if len(image_bytes) == 0:
            results.append({"filename": file.filename, "error": "Пустой файл"})
            continue

        start_time = time.time()
        result = moderator.classify_image_bytes(image_bytes)
        inference_time = round((time.time() - start_time) * 1000, 1)

        result["inference_time_ms"] = inference_time
        result["filename"] = file.filename
        result["action_taken"] = "partial_blur" if result["is_unsafe"] else "allowed"

        results.append(result)

    return JSONResponse(content={"results": results, "total": len(results)})


@app.get("/api/health")
async def health():
    """Проверка работоспособности сервера."""
    return {
        "status": "ok",
        "model_loaded": moderator is not None,
        "model_path": MODEL_PATH,
    }


# --- Запуск ---

if __name__ == "__main__":
    import uvicorn

    print("=" * 60)
    print("  Content Moderation Tester")
    print("  Откройте http://localhost:8000 в браузере")
    print("=" * 60)
    uvicorn.run(app, host="0.0.0.0", port=8000)
