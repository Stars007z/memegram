"""
Модуль инференса модели контент-модерации.

Стратегия partial blur:
1. ОСНОВНОЙ детектор: NudeNet — даёт точные bounding boxes для гениталий/сосков/ануса.
   Работает идеально на реальных фото.
2. FALLBACK: скользящее окно + тепловая карта (для аниме/рисунков, где NudeNet молчит).

ViT-L14 модель используется только для общей классификации (UNSAFE/SAFE верхнего уровня).
"""

import os
import sys

# === UTF-8 stdout/stderr (Windows cp1251 крашит print со стрелками и эмодзи) ===
try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    sys.stderr.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass

# === Локальные пути к моделям ===
# Все модели должны лежать в ./models/ (относительно этого файла), без интернет-загрузок.
_MODULE_DIR = os.path.dirname(os.path.abspath(__file__))
MODELS_DIR = os.path.join(_MODULE_DIR, "models")

# HuggingFace cache → ./models/hf (для OWL и dghs-imgutils)
os.environ.setdefault("HF_HOME", os.path.join(MODELS_DIR, "hf"))
os.environ.setdefault("HF_HUB_CACHE", os.path.join(MODELS_DIR, "hf"))
# Подавляем warning про симлинки на Windows (не критично, просто дублируются файлы)
os.environ.setdefault("HF_HUB_DISABLE_SYMLINKS_WARNING", "1")
# NudeNet cache → ./models/nudenet
os.environ.setdefault("NUDENET_CACHE_DIR", os.path.join(MODELS_DIR, "nudenet"))
os.makedirs(os.environ["HF_HOME"], exist_ok=True)
os.makedirs(os.environ["NUDENET_CACHE_DIR"], exist_ok=True)

import tempfile
import numpy as np
import onnxruntime as ort
import cv2

# Максимальный размер входного изображения по большей стороне.
# Большие картинки (4K+) замедляют все этапы (decode, sliding window crops, NudeNet).
# 1280px достаточно для точной детекции, ускоряет 4K в ~10x.
MAX_INPUT_DIM = 1280

# Классы модели (порядок соответствует выходу модели)
CLASS_LABELS = ["alcohol", "gore", "military", "nudity", "smoking", "negative"]

CLASS_LABELS_RU = {
    "alcohol": "Алкоголь",
    "gore": "Жестокость / Кровь",
    "military": "Военные / Оружие",
    "nudity": "Обнажённость",
    "smoking": "Курение",
    "negative": "Негативный контент",
}

UNSAFE_CLASSES = {"alcohol", "gore", "military", "nudity", "smoking", "negative"}
BASELINE_PROB = 1.0 / len(CLASS_LABELS)
DEFAULT_THRESHOLD = 1.8

# Per-class абсолютные пороги UNSAFE (override для default threshold * baseline).
# Используется когда обычный порог даёт false negative для конкретного класса.
PER_CLASS_UNSAFE_OVERRIDE = {
    "smoking": 0.23,  # сигарета часто детектится моделью с уверенностью 23-28%
}

# --- NudeNet классы для блюра ---
# Основные unsafe-зоны (всегда блюрим)
NUDENET_BLUR_CLASSES_PRIMARY = {
    "FEMALE_GENITALIA_EXPOSED",
    "MALE_GENITALIA_EXPOSED",
    "ANUS_EXPOSED",
    "FEMALE_BREAST_EXPOSED",
    "BUTTOCKS_EXPOSED",
}
# Минимальный порог уверенности NudeNet для бокса
NUDENET_MIN_SCORE = 0.30

# --- Anime censor detector (dghs-imgutils) ---
# Метки anime-детектора которые блюрим
ANIME_CENSOR_LABELS = {"nipple_f", "penis", "pussy"}
ANIME_CENSOR_MIN_SCORE = 0.30

# --- OWL-ViTv2 (включён для negative И military — символы ненависти) ---
# ViT-L14 не различает свастику и обнажённый торс (оба дают negative≈24.7%).
# Расширено на military: nazi armband часто соседствует с военной формой,
# и тогда predicted=military/smoking, negative<23%.
USE_OWL = True
# Per-class thresholds для запуска OWL. OWL стоит ~10с на CPU,
# поэтому запускаем его только при достаточной уверенности ViT.
# - negative: 0.20 (низкий — OWL единственный способ локализовать свастику,
#   а ViT даёт ей всего ~25%)
# - military: 0.30 (высокий — для military обычно хватает sliding-window;
#   OWL только при сильной уверенности что это форма с символами)
OWL_TRIGGER_CLASSES = {"negative", "military"}  # совместимость со старым кодом
OWL_TRIGGER_PROB_PER_CLASS = {
    "negative": 0.20,
    "military": 0.30,
}
# Глобальный fallback (используется если класс не в per-class словаре)
OWL_TRIGGER_PROB = 0.20
# Сокращённый набор labels (4 наиболее эффективных по логам).
OWL_LABELS = [
    "a swastika",
    "a red armband with black symbol",
    "a nazi armband",
    "a hate symbol",
]
OWL_MIN_SCORE = 0.10
# Resize до 384px (вместо 640) — ускорение OWL ~2.5x с минимальной потерей качества
# на крупных объектах (флаг свастики). Мелкие armband могут пропускаться.
OWL_MAX_DIM = 384

# --- Sliding window: триггеры по классам ---
# Если any класс из списка >= порог, запускаем sliding window нацеленное на этот класс.
# Используется per-class heatmap (вероятности именно этого класса в каждом окне),
# а не max prob по всем классам. Так ловим сигарету / алкоголь / оружие точнее.
SLIDING_WINDOW_CLASS_TRIGGERS = {
    # class: (trigger_image_level_threshold, per_crop_threshold)
    "smoking":  (0.23, 0.18),
    "alcohol":  (0.23, 0.18),
    "gore":     (0.23, 0.18),
    "military": (0.23, 0.18),
    # nudity и negative целенаправленно НЕ здесь:
    # - nudity ловят NudeNet + anime detector (с fallback)
    # - negative ловит OWL-ViTv2 (точная детекция hate symbols)
}

# Размер сетки sliding window (4x4=16 сканов; ~2-2.3с CPU).
# Уменьшено с 5x5 ради 5с бюджета. Окна 30% (vs прежние 25%) сохраняют
# покрытие при меньшей сетке.
SLIDING_WINDOW_GRID = 4

# Размер окна как доля от стороны изображения. 0.30 = 30%.
SLIDING_WINDOW_FRACTION = 0.30

# Full-blur требует чтобы max score триггер-класса был ≥ этого порога.
# Защита от borderline-кейсов: если negative=24.7% (еле над 23%), full-blur
# слишком агрессивен — лучше точечный narrowing или ничего.
FULL_BLUR_MIN_SCORE = 0.35

# Fallback порог для nudity sliding window (когда NudeNet+anime detector молчат,
# но классификатор уверен в nudity). Используется только в этой ситуации.
NUDITY_FALLBACK_TRIGGER = (0.23, 0.18)

# Классы которые НИКОГДА не блюрим если они dominant (нет точечной локализации).
# Пусто — даже "negative" теперь обрабатывается через sliding window.
NEVER_BLUR_DOMINANT = set()


def softmax(x: np.ndarray) -> np.ndarray:
    """Вычисляет softmax по последней оси."""
    e_x = np.exp(x - np.max(x, axis=-1, keepdims=True))
    return e_x / e_x.sum(axis=-1, keepdims=True)


class ContentModerator:
    """Класс для модерации изображений с помощью ONNX-модели + NudeNet."""

    def __init__(self, model_path: str, threshold: float = DEFAULT_THRESHOLD):
        if not os.path.exists(model_path):
            raise FileNotFoundError(f"Модель не найдена: {model_path}")

        providers = []
        available = ort.get_available_providers()
        if "CUDAExecutionProvider" in available:
            providers.append("CUDAExecutionProvider")
        providers.append("CPUExecutionProvider")

        self.session = ort.InferenceSession(model_path, providers=providers)
        self.input_name = self.session.get_inputs()[0].name
        self.output_name = self.session.get_outputs()[0].name
        self.threshold = threshold

        print(f"[ContentModerator] ViT модель: {os.path.basename(model_path)}")
        print(f"[ContentModerator] Провайдер: {self.session.get_providers()}")
        print(f"[ContentModerator] Порог: {self.threshold}")

        # Lazy-load NudeNet
        self._nude_detector = None
        # Lazy-load anime censor detector (dghs-imgutils)
        self._anime_detector_ready = None  # True/False; функция detect_censors импортируется
        # Lazy-load OWL-ViTv2
        self._owl_pipeline = None
        self._owl_failed = False

    @property
    def nude_detector(self):
        """Ленивая загрузка NudeNet (используем локальный ONNX из ./models/nudenet/)."""
        if self._nude_detector is None:
            from nudenet import NudeDetector
            print("[ContentModerator] Загружаю NudeDetector...")
            local_path = os.path.join(MODELS_DIR, "nudenet", "320n.onnx")
            if os.path.exists(local_path):
                self._nude_detector = NudeDetector(model_path=local_path)
                print(f"[ContentModerator] NudeDetector готов (local: {local_path})")
            else:
                self._nude_detector = NudeDetector()
                print("[ContentModerator] NudeDetector готов (bundled)")
        return self._nude_detector

    def _ensure_anime_detector(self) -> bool:
        """Проверяет доступность dghs-imgutils. Возвращает True если готов."""
        if self._anime_detector_ready is None:
            try:
                from imgutils.detect import detect_censors  # noqa: F401
                self._anime_detector_ready = True
                print("[ContentModerator] Anime censor detector доступен")
            except Exception as e:
                print(f"[ContentModerator] Anime censor detector недоступен: {e}")
                self._anime_detector_ready = False
        return self._anime_detector_ready

    @property
    def owl_pipeline(self):
        """Ленивая загрузка OWL-ViTv2 (base — быстрее ensemble в ~2 раза)."""
        if self._owl_pipeline is None and not self._owl_failed:
            try:
                from transformers import pipeline
                print("[ContentModerator] Загружаю OWL-ViTv2-base (может занять 30-60с)...")
                self._owl_pipeline = pipeline(
                    "zero-shot-object-detection",
                    model="google/owlv2-base-patch16",
                )
                print("[ContentModerator] OWL-ViTv2-base готов")
            except Exception as e:
                print(f"[ContentModerator] OWL-ViTv2 загрузка провалилась: {e}")
                self._owl_failed = True
        return self._owl_pipeline

    # --- Препроцессинг ---

    def preprocess(self, image_bytes: bytes) -> np.ndarray:
        nparr = np.frombuffer(image_bytes, np.uint8)
        img = cv2.imdecode(nparr, cv2.IMREAD_COLOR)
        if img is None:
            raise ValueError("Не удалось декодировать изображение")
        img = cv2.cvtColor(img, cv2.COLOR_BGR2RGB)
        img = cv2.resize(img, (336, 336), interpolation=cv2.INTER_AREA)
        return np.expand_dims(img, axis=0).astype(np.uint8)

    def preprocess_cv2(self, img: np.ndarray) -> np.ndarray:
        img_rgb = cv2.cvtColor(img, cv2.COLOR_BGR2RGB)
        img_resized = cv2.resize(img_rgb, (336, 336), interpolation=cv2.INTER_AREA)
        return np.expand_dims(img_resized, axis=0).astype(np.uint8)

    # --- Классификация ---

    def predict(self, input_tensor: np.ndarray) -> dict:
        raw_output = self.session.run([self.output_name], {self.input_name: input_tensor})
        logits = raw_output[0]
        probabilities = softmax(logits)[0]

        class_scores = {label: float(probabilities[i]) for i, label in enumerate(CLASS_LABELS)}
        predicted_idx = int(np.argmax(probabilities))
        predicted_label = CLASS_LABELS[predicted_idx]
        predicted_confidence = float(probabilities[predicted_idx])

        # Стандартный порог: baseline * threshold (например 0.167 * 1.8 = 0.30)
        default_unsafe_threshold = BASELINE_PROB * self.threshold

        # Проверяем стандартный порог
        is_unsafe = predicted_confidence >= default_unsafe_threshold

        # Per-class override: если какой-то класс имеет пониженный порог и
        # его score >= override, помечаем UNSAFE даже если он не топ-1
        unsafe_reason = None
        if not is_unsafe:
            for cls, override_thr in PER_CLASS_UNSAFE_OVERRIDE.items():
                if class_scores.get(cls, 0) >= override_thr:
                    is_unsafe = True
                    # Переключаем predicted на этот класс для UX
                    predicted_label = cls
                    predicted_confidence = class_scores[cls]
                    unsafe_reason = f"per-class override ({cls} >= {override_thr})"
                    break
        elif predicted_label in PER_CLASS_UNSAFE_OVERRIDE:
            unsafe_reason = "default threshold"

        return {
            "predicted_class": predicted_label,
            "predicted_class_ru": CLASS_LABELS_RU[predicted_label],
            "confidence": round(predicted_confidence, 4),
            "is_unsafe": is_unsafe,
            "all_scores": {label: round(score, 4) for label, score in class_scores.items()},
            "threshold": self.threshold,
            "effective_threshold_pct": round(BASELINE_PROB * self.threshold * 100, 1),
            "unsafe_reason": unsafe_reason,
        }

    def _predict_raw(self, img_bgr: np.ndarray) -> tuple[float, str]:
        """Быстрый инференс → (max_probability, class_label)."""
        input_tensor = self.preprocess_cv2(img_bgr)
        raw_output = self.session.run([self.output_name], {self.input_name: input_tensor})
        probs = softmax(raw_output[0])[0]
        idx = int(np.argmax(probs))
        return float(probs[idx]), CLASS_LABELS[idx]

    def _predict_full_probs(self, img_bgr: np.ndarray) -> dict:
        """Возвращает полный словарь {class_label: probability}."""
        input_tensor = self.preprocess_cv2(img_bgr)
        raw_output = self.session.run([self.output_name], {self.input_name: input_tensor})
        probs = softmax(raw_output[0])[0]
        return {label: float(probs[i]) for i, label in enumerate(CLASS_LABELS)}

    def classify_image_bytes(self, image_bytes: bytes) -> dict:
        return self.predict(self.preprocess(image_bytes))

    # --- Partial blur: главный пайплайн ---

    def partial_blur_image(
        self,
        image_bytes: bytes,
        predicted_class: str | None = None,
        is_unsafe: bool | None = None,
        all_scores: dict | None = None,
    ) -> tuple[bytes, dict]:
        """
        Главный метод partial blur.

        Логика:
        1. Всегда пробуем NudeNet (быстро, ~100ms) — точные nudity-боксы
        2. Запускаем sliding window если есть подозрение на не-nudity unsafe:
           любой класс кроме nudity набрал >= 20% (1.2x baseline)
           ИЛИ если общий вердикт = unsafe + класс не nudity
        3. Объединяем маски от NudeNet и sliding window

        Args:
            image_bytes: байты изображения
            predicted_class: уже известный класс (если None — классифицируем сами)
            is_unsafe: уже известно safe/unsafe
            all_scores: уже известны вероятности классов

        Returns:
            (байты PNG, dict с метаданными)
        """
        nparr = np.frombuffer(image_bytes, np.uint8)
        img = cv2.imdecode(nparr, cv2.IMREAD_COLOR)
        if img is None:
            raise ValueError("Не удалось декодировать изображение")

        # Resize больших изображений до MAX_INPUT_DIM по большей стороне.
        # Big-image outliers (4K+) могут давать 15-20с обработку из-за тяжёлых
        # crop-операций в sliding window и NudeNet. Resize до 1280 даёт ~5x ускорение.
        orig_h, orig_w = img.shape[:2]
        if max(orig_h, orig_w) > MAX_INPUT_DIM:
            scale = MAX_INPUT_DIM / max(orig_h, orig_w)
            new_w = int(orig_w * scale)
            new_h = int(orig_h * scale)
            img = cv2.resize(img, (new_w, new_h), interpolation=cv2.INTER_AREA)
            print(f"[PartialBlur] Resize {orig_w}x{orig_h} → {new_w}x{new_h} "
                  f"(MAX_INPUT_DIM={MAX_INPUT_DIM})")
            # Перекодируем для NudeNet (он берёт image_bytes)
            _, buf = cv2.imencode(".jpg", img, [cv2.IMWRITE_JPEG_QUALITY, 92])
            image_bytes = buf.tobytes()

        h, w = img.shape[:2]

        # Если данные не переданы — классифицируем сами
        if predicted_class is None or is_unsafe is None or all_scores is None:
            whole_result = self.classify_image_bytes(image_bytes)
            predicted_class = whole_result["predicted_class"]
            is_unsafe = whole_result["is_unsafe"]
            all_scores = whole_result["all_scores"]

        combined_mask = np.zeros((h, w), dtype=np.uint8)
        info = {"method": "combined", "stages": []}

        print(f"[PartialBlur] predicted={predicted_class}, is_unsafe={is_unsafe}")
        print(f"[PartialBlur] all_scores={ {k: round(v,3) for k,v in all_scores.items()} }")

        # === ВАЖНО: если dominant класс из NEVER_BLUR_DOMINANT — отдаём оригинал ===
        # Например "negative content" не имеет точечной локализации, блюрить нечего.
        if predicted_class in NEVER_BLUR_DOMINANT:
            print(f"[PartialBlur] Класс '{predicted_class}' в NEVER_BLUR_DOMINANT — пропускаем все этапы блюра")
            info["stages"].append({
                "method": "skipped",
                "reason": f"dominant class '{predicted_class}' has no point localization",
            })
            info["predicted_class"] = predicted_class
            return self._apply_blur_with_mask(img, combined_mask, info)

        # --- Этап 1: NudeNet (всегда пробуем) ---
        nudenet_result = self._try_nudenet(image_bytes, h, w)
        if nudenet_result is not None:
            nn_mask, nn_info = nudenet_result
            combined_mask = np.maximum(combined_mask, nn_mask)
            info["stages"].append(nn_info)

        # --- Этап 1b: Anime censor detector (всегда пробуем) ---
        anime_result = self._try_anime_censor(img, h, w)
        if anime_result is not None:
            a_mask, a_info = anime_result
            combined_mask = np.maximum(combined_mask, a_mask)
            info["stages"].append(a_info)

        # --- Этап 2: Class-aware sliding window ---
        # Собираем список классов которые "загорелись" на image-level
        triggered_classes = []
        for cls, (img_thr, _crop_thr) in SLIDING_WINDOW_CLASS_TRIGGERS.items():
            if all_scores.get(cls, 0) >= img_thr:
                triggered_classes.append(cls)

        # Nudity fallback: если ни NudeNet, ни anime detector ничего не нашли,
        # но классификатор уверен что это nudity → FULL-BLUR всего изображения.
        # Sliding window на ViT-L14 мис-локализует (блюрит лица вместо объекта),
        # поэтому используем безопасный full-blur вместо точечной маски.
        nudity_localized = nudenet_result is not None or anime_result is not None
        nudity_score = all_scores.get("nudity", 0)
        if (
            not nudity_localized
            and nudity_score >= NUDITY_FALLBACK_TRIGGER[0]
            and predicted_class == "nudity"
        ):
            print(f"[PartialBlur] Nudity fallback FULL-BLUR: NudeNet+anime пусты, "
                  f"nudity={nudity_score:.3f} → блюрим всё изображение")
            full_mask = np.full((h, w), 255, dtype=np.uint8)
            combined_mask = np.maximum(combined_mask, full_mask)
            info["stages"].append({
                "method": "nudity_fallback_full_blur",
                "nudity_score": round(nudity_score, 3),
                "reason": "NudeNet+anime detector found nothing but nudity≥23%",
            })

        if triggered_classes:
            print(f"[PartialBlur] Sliding window триггеры: {triggered_classes}")
            sw_mask, sw_info = self._sliding_window_mask(
                img, target_classes=triggered_classes, all_scores=all_scores
            )
            combined_mask = np.maximum(combined_mask, sw_mask)
            info["stages"].append(sw_info)

        # --- Этап 3: OWL-ViTv2 hate symbols (всегда активен) ---
        # Раньше OWL запускался только при negative>=0.20 или military>=0.30,
        # но ViT-L14 даёт свастике всего ~25% negative и иногда <20% — тогда
        # OWL не стартовал и свастика проходила как safe. Теперь гоним OWL
        # всегда: он сам решает по своим OWL_MIN_SCORE есть ли символы.
        # Стоимость ~5-10с CPU на фото, но проверка идёт в фоне.
        if USE_OWL:
            negative_score = all_scores.get("negative", 0)
            military_score = all_scores.get("military", 0)
            print(
                f"[PartialBlur] OWL forced=True "
                f"(negative={negative_score:.3f}, military={military_score:.3f})"
            )
            owl_result = self._try_owl_hate_symbols(img, h, w)
            if owl_result is not None:
                o_mask, o_info = owl_result
                combined_mask = np.maximum(combined_mask, o_mask)
                info["stages"].append(o_info)

        # Если ни один этап ничего не нашёл — пустая маска (отдадим оригинал)
        if not info["stages"]:
            info["stages"].append({"method": "none", "reason": "no triggers"})

        info["predicted_class"] = predicted_class
        return self._apply_blur_with_mask(img, combined_mask, info)

    # --- NudeNet branch ---

    def _try_nudenet(self, image_bytes: bytes, h: int, w: int):
        """
        Запускает NudeNet. Возвращает (mask, info) или None если ничего не найдено.
        """
        # NudeDetector.detect() требует путь к файлу (или работает с bytes — проверим)
        # В новых версиях принимает np.ndarray
        try:
            nparr = np.frombuffer(image_bytes, np.uint8)
            img_arr = cv2.imdecode(nparr, cv2.IMREAD_COLOR)
            try:
                detections = self.nude_detector.detect(img_arr)
            except Exception:
                # Fallback: записываем во временный файл
                with tempfile.NamedTemporaryFile(suffix=".jpg", delete=False) as tmp:
                    tmp.write(image_bytes)
                    tmp_path = tmp.name
                try:
                    detections = self.nude_detector.detect(tmp_path)
                finally:
                    try:
                        os.unlink(tmp_path)
                    except OSError:
                        pass
        except Exception as e:
            print(f"[NudeNet] Ошибка детекции: {e}")
            return None

        if not detections:
            return None

        # Фильтруем нужные классы с приличным скором
        relevant = [
            d for d in detections
            if d.get("class") in NUDENET_BLUR_CLASSES_PRIMARY
            and d.get("score", 0) >= NUDENET_MIN_SCORE
        ]

        print(f"[NudeNet] Найдено {len(detections)} объектов, релевантных {len(relevant)}")
        for d in relevant:
            print(f"  - {d['class']}: score={d['score']:.2f}, box={d['box']}")

        if not relevant:
            return None

        # Строим маску из боксов с padding 15%
        mask = np.zeros((h, w), dtype=np.uint8)
        for d in relevant:
            x, y, bw, bh = d["box"]
            # padding 15% от размера бокса
            pad_x = int(bw * 0.15)
            pad_y = int(bh * 0.15)
            x1 = max(0, x - pad_x)
            y1 = max(0, y - pad_y)
            x2 = min(w, x + bw + pad_x)
            y2 = min(h, y + bh + pad_y)
            # Заполняем эллипсом (более органичные края чем прямоугольник)
            cx = (x1 + x2) // 2
            cy = (y1 + y2) // 2
            ax = (x2 - x1) // 2
            ay = (y2 - y1) // 2
            cv2.ellipse(mask, (cx, cy), (ax, ay), 0, 0, 360, 255, -1)

        info = {
            "method": "nudenet",
            "detections_total": len(detections),
            "detections_relevant": len(relevant),
            "boxes": [
                {"class": d["class"], "score": round(float(d["score"]), 3), "box": d["box"]}
                for d in relevant
            ],
        }
        return mask, info

    # --- Anime censor branch (dghs-imgutils) ---

    def _try_anime_censor(self, img_bgr: np.ndarray, h: int, w: int):
        """
        Запускает anime_censor_detection. Возвращает (mask, info) или None.
        Детектит: nipple_f, penis, pussy на аниме/рисунках.
        """
        if not self._ensure_anime_detector():
            return None

        try:
            from imgutils.detect import detect_censors
            from PIL import Image

            img_rgb = cv2.cvtColor(img_bgr, cv2.COLOR_BGR2RGB)
            pil_img = Image.fromarray(img_rgb)
            # detect_censors возвращает список [((x0,y0,x1,y1), label, score), ...]
            detections = detect_censors(pil_img)
        except Exception as e:
            print(f"[AnimeCensor] Ошибка детекции: {e}")
            return None

        if not detections:
            print("[AnimeCensor] Ничего не найдено")
            return None

        relevant = [
            d for d in detections
            if d[1] in ANIME_CENSOR_LABELS and d[2] >= ANIME_CENSOR_MIN_SCORE
        ]

        print(f"[AnimeCensor] Найдено {len(detections)} объектов, релевантных {len(relevant)}")
        for bbox, label, score in relevant:
            print(f"  - {label}: score={score:.2f}, box={bbox}")

        if not relevant:
            return None

        mask = np.zeros((h, w), dtype=np.uint8)
        for bbox, _label, _score in relevant:
            x1, y1, x2, y2 = map(int, bbox)
            bw = x2 - x1
            bh = y2 - y1
            pad_x = int(bw * 0.15)
            pad_y = int(bh * 0.15)
            x1 = max(0, x1 - pad_x)
            y1 = max(0, y1 - pad_y)
            x2 = min(w, x2 + pad_x)
            y2 = min(h, y2 + pad_y)
            cx = (x1 + x2) // 2
            cy = (y1 + y2) // 2
            ax = (x2 - x1) // 2
            ay = (y2 - y1) // 2
            cv2.ellipse(mask, (cx, cy), (ax, ay), 0, 0, 360, 255, -1)

        info = {
            "method": "anime_censor",
            "detections_total": len(detections),
            "detections_relevant": len(relevant),
            "boxes": [
                {"class": lbl, "score": round(float(sc), 3),
                 "box": [int(v) for v in bb]}
                for bb, lbl, sc in relevant
            ],
        }
        return mask, info

    # --- OWL-ViTv2 hate symbols branch ---

    def _try_owl_hate_symbols(self, img_bgr: np.ndarray, h: int, w: int):
        """
        Zero-shot детекция свастики/nazi символов через OWL-ViTv2.
        Медленно (3-8с CPU). Возвращает (mask, info) или None.
        """
        pipe = self.owl_pipeline
        if pipe is None:
            return None

        try:
            from PIL import Image
            img_rgb = cv2.cvtColor(img_bgr, cv2.COLOR_BGR2RGB)

            # Resize до OWL_MAX_DIM по большей стороне для ускорения инференса.
            # Боксы потом масштабируем обратно к оригинальным размерам.
            orig_h, orig_w = img_rgb.shape[:2]
            scale = 1.0
            if max(orig_h, orig_w) > OWL_MAX_DIM:
                scale = OWL_MAX_DIM / max(orig_h, orig_w)
                new_w = int(orig_w * scale)
                new_h = int(orig_h * scale)
                img_rgb_small = cv2.resize(img_rgb, (new_w, new_h), interpolation=cv2.INTER_AREA)
                print(f"[OWL] Resize {orig_w}x{orig_h} → {new_w}x{new_h} (scale={scale:.3f})")
            else:
                img_rgb_small = img_rgb

            pil_img = Image.fromarray(img_rgb_small)
            print("[OWL] Запуск zero-shot детекции...")
            # Сначала пробуем БЕЗ threshold чтобы увидеть raw scores для диагностики
            raw_results = pipe(pil_img, candidate_labels=OWL_LABELS)
            if raw_results:
                top5 = sorted(raw_results, key=lambda r: r.get("score", 0), reverse=True)[:5]
                print(f"[OWL] Raw top-5: " + ", ".join(
                    f"{r.get('label','?')}={r.get('score',0):.3f}" for r in top5
                ))
            results = [r for r in raw_results if r.get("score", 0) >= OWL_MIN_SCORE]
        except Exception as e:
            import traceback
            print(f"[OWL] !!! Ошибка детекции: {type(e).__name__}: {e}")
            traceback.print_exc()
            return None

        if not results:
            print("[OWL] Ничего не найдено")
            return None

        # Обратное масштабирование боксов к оригинальным размерам
        inv_scale = 1.0 / scale if scale != 1.0 else 1.0

        print(f"[OWL] Найдено {len(results)} объектов")
        mask = np.zeros((h, w), dtype=np.uint8)
        boxes_info = []
        for r in results:
            box = r.get("box", {})
            score = float(r.get("score", 0))
            label = r.get("label", "?")
            x1 = max(0, int(box.get("xmin", 0) * inv_scale))
            y1 = max(0, int(box.get("ymin", 0) * inv_scale))
            x2 = min(w, int(box.get("xmax", 0) * inv_scale))
            y2 = min(h, int(box.get("ymax", 0) * inv_scale))
            if x2 <= x1 or y2 <= y1:
                continue
            bw = x2 - x1
            bh = y2 - y1
            # Агрессивный padding 40% для hate symbols — символ должен быть полностью скрыт
            pad_x = int(bw * 0.40)
            pad_y = int(bh * 0.40)
            x1 = max(0, x1 - pad_x)
            y1 = max(0, y1 - pad_y)
            x2 = min(w, x2 + pad_x)
            y2 = min(h, y2 + pad_y)
            cx = (x1 + x2) // 2
            cy = (y1 + y2) // 2
            ax = (x2 - x1) // 2
            ay = (y2 - y1) // 2
            cv2.ellipse(mask, (cx, cy), (ax, ay), 0, 0, 360, 255, -1)
            print(f"  - {label}: score={score:.2f}, box=[{x1},{y1},{x2},{y2}]")
            boxes_info.append({
                "class": label, "score": round(score, 3),
                "box": [x1, y1, x2, y2],
            })

        if not boxes_info:
            return None

        info = {
            "method": "owl_hate_symbols",
            "detections_relevant": len(boxes_info),
            "boxes": boxes_info,
        }
        return mask, info

    # --- Sliding window: class-aware heatmap ---

    def _sliding_window_mask(
        self,
        img: np.ndarray,
        target_classes: list[str] | None = None,
        all_scores: dict | None = None,
    ) -> tuple[np.ndarray, dict]:
        """
        Class-aware sliding window.

        Если target_classes указан, строим тепловую карту от max(prob[c] for c in target_classes)
        для каждого окна, и порог берём из SLIDING_WINDOW_CLASS_TRIGGERS (per_crop_threshold).

        Если target_classes пуст/None — fallback на старое поведение (max prob по всем классам).
        """
        h, w = img.shape[:2]
        win_h = max(int(h * SLIDING_WINDOW_FRACTION), 64)
        win_w = max(int(w * SLIDING_WINDOW_FRACTION), 64)
        grid_steps = SLIDING_WINDOW_GRID
        step_y = (h - win_h) / max(grid_steps - 1, 1) if grid_steps > 1 else 0
        step_x = (w - win_w) / max(grid_steps - 1, 1) if grid_steps > 1 else 0

        heatmap_sum = np.zeros((h, w), dtype=np.float64)
        heatmap_count = np.zeros((h, w), dtype=np.float64)

        total_scans = grid_steps * grid_steps
        triggered_scans = 0

        # Determine per-crop threshold
        if target_classes:
            per_crop_thresholds = []
            for c in target_classes:
                if c in SLIDING_WINDOW_CLASS_TRIGGERS:
                    per_crop_thresholds.append(SLIDING_WINDOW_CLASS_TRIGGERS[c][1])
                elif c == "nudity":
                    per_crop_thresholds.append(NUDITY_FALLBACK_TRIGGER[1])
            crop_threshold = min(per_crop_thresholds) if per_crop_thresholds else BASELINE_PROB
            mode = f"class-aware ({','.join(target_classes)})"
        else:
            crop_threshold = BASELINE_PROB * self.threshold
            mode = "max-prob fallback"

        print(f"[SlidingWindow] {total_scans} сканов, окно {win_w}x{win_h}, "
              f"режим={mode}, crop_threshold={crop_threshold:.3f}")

        for row in range(grid_steps):
            for col in range(grid_steps):
                y = int(row * step_y)
                x = int(col * step_x)
                y2 = min(y + win_h, h)
                x2 = min(x + win_w, w)
                crop = img[y:y2, x:x2]

                if target_classes:
                    probs = self._predict_full_probs(crop)
                    crop_score = max(probs.get(c, 0.0) for c in target_classes)
                else:
                    crop_score, _ = self._predict_raw(crop)

                if crop_score >= crop_threshold:
                    triggered_scans += 1
                heatmap_sum[y:y2, x:x2] += crop_score
                heatmap_count[y:y2, x:x2] += 1.0

        heatmap_count = np.maximum(heatmap_count, 1.0)
        avg_heatmap = heatmap_sum / heatmap_count

        blur_threshold = crop_threshold
        triggered_ratio = triggered_scans / total_scans

        # === Случай 1: контент заполняет почти всё изображение ===
        # Если ≥85% окон триггернулись И уверенность модели высокая (≥35%),
        # объект (флаг, форма, кровь, дым) занимает большую часть кадра.
        # При borderline confidence (24-30%) full-blur даёт false positives
        # на безобидном контенте (топлесс, тёмные сцены и т.п.).
        target_max_score = 0.0
        if all_scores and target_classes:
            target_max_score = max(all_scores.get(c, 0) for c in target_classes)

        if triggered_ratio >= 0.85 and target_max_score >= FULL_BLUR_MIN_SCORE:
            print(f"[SlidingWindow] Full-blur: {triggered_ratio*100:.0f}% окон, "
                  f"max target score={target_max_score:.3f} ≥ {FULL_BLUR_MIN_SCORE}")
            mask = np.full((h, w), 255, dtype=np.uint8)
            info = {
                "method": "sliding_window",
                "target_classes": target_classes or [],
                "total_scans": total_scans,
                "unsafe_scans": triggered_scans,
                "window_size": f"{win_w}x{win_h}",
                "blur_threshold_used": round(crop_threshold, 4),
                "adaptive": "full_blur",
            }
            return mask, info

        if triggered_ratio >= 0.85:
            print(f"[SlidingWindow] {triggered_ratio*100:.0f}% окон триггернулись, "
                  f"но max target score={target_max_score:.3f} < {FULL_BLUR_MIN_SCORE} "
                  f"→ borderline, narrowing вместо full-blur")

        # === Случай 2: умеренное количество окон → adaptive narrowing ===
        # Если ≥70% триггернулись, поднимаем порог до 70-го перцентиля.
        if triggered_ratio > 0.70:
            p70 = float(np.percentile(avg_heatmap, 70))
            blur_threshold = max(crop_threshold, p70)
            print(f"[SlidingWindow] Adaptive: {triggered_ratio*100:.0f}% triggered → "
                  f"порог {crop_threshold:.3f} → {blur_threshold:.3f}")

        mask = (avg_heatmap >= blur_threshold).astype(np.uint8) * 255

        kernel = cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (15, 15))
        mask = cv2.morphologyEx(mask, cv2.MORPH_CLOSE, kernel, iterations=2)
        mask = cv2.morphologyEx(mask, cv2.MORPH_OPEN, kernel, iterations=1)

        info = {
            "method": "sliding_window",
            "target_classes": target_classes or [],
            "total_scans": total_scans,
            "unsafe_scans": triggered_scans,
            "window_size": f"{win_w}x{win_h}",
            "blur_threshold_used": round(blur_threshold, 4),
            "adaptive": triggered_ratio > 0.70,
        }
        return mask, info

    # --- Применение блюра ---

    def _apply_blur_with_mask(
        self, img: np.ndarray, mask: np.ndarray, info: dict
    ) -> tuple[bytes, dict]:
        """
        Применяет блюр на области заданные маской с мягким feathering краёв.
        """
        h, w = img.shape[:2]

        # Размер ядра feathering зависит от размера изображения
        smooth_k = max(min(h, w) // 20, 21)
        if smooth_k % 2 == 0:
            smooth_k += 1

        # Smooth мask edges
        smooth_mask = cv2.GaussianBlur(mask.astype(np.float32), (smooth_k, smooth_k), 0)
        mask_max = smooth_mask.max()
        if mask_max > 0:
            smooth_mask = smooth_mask / mask_max
        else:
            smooth_mask = np.zeros((h, w), dtype=np.float32)

        # Создаём ПЛОТНЫЙ блюр: сильная пикселизация (8x8) + 5 проходов Gaussian
        tiny = cv2.resize(img, (8, 8), interpolation=cv2.INTER_LINEAR)
        pixelated = cv2.resize(tiny, (w, h), interpolation=cv2.INTER_NEAREST)
        blurred = pixelated
        for _ in range(5):
            blurred = cv2.GaussianBlur(blurred, (151, 151), 0)

        # Композит
        mask_3d = np.stack([smooth_mask] * 3, axis=-1)
        result = (
            img.astype(np.float64) * (1.0 - mask_3d)
            + blurred.astype(np.float64) * mask_3d
        ).astype(np.uint8)

        # Статистика
        unsafe_pixels = int(np.sum(mask > 0))
        total_pixels = h * w
        unsafe_area_pct = round(unsafe_pixels / total_pixels * 100, 1)

        info["unsafe_area_pct"] = unsafe_area_pct
        info["blurred"] = unsafe_area_pct > 0

        print(f"[PartialBlur] Метод={info['method']}, заблюрено {unsafe_area_pct}%")

        _, buffer = cv2.imencode(".png", result)
        return buffer.tobytes(), info
