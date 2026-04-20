# NSFW / Content moderation service
# Wraps ml/nsfw/content_moderation_test/app.py as a containerised FastAPI service.
#
# Models (NudeNet, anime censor detector, ViT-L14, OWLv2) are loaded from the
# mounted volume at runtime — no model is baked into the image so we keep it
# small and rebuilds are fast. On first start `download_models.py` will pull
# anything missing into the mounted ./models directory.

FROM python:3.11-slim

ENV PYTHONUNBUFFERED=1 \
    PYTHONDONTWRITEBYTECODE=1 \
    PIP_NO_CACHE_DIR=1 \
    HF_HUB_DISABLE_SYMLINKS_WARNING=1 \
    # All HF / NudeNet caches will live under /app/models which is bind-mounted
    HF_HOME=/app/models/hf \
    HF_HUB_CACHE=/app/models/hf \
    NUDENET_CACHE_DIR=/app/models/nudenet

WORKDIR /app

# System libs needed by opencv-python-headless and onnxruntime
RUN apt-get update && apt-get install -y --no-install-recommends \
        libgl1 \
        libglib2.0-0 \
        libgomp1 \
        curl \
    && rm -rf /var/lib/apt/lists/*

# Install Python deps. Notes:
#  - torch is pulled from the CPU-only index to keep the image ~1 GB instead of 3+ GB.
#  - dghs-imgutils must be installed with --no-deps to avoid pinning numpy<2,
#    which would break onnxruntime / transformers.
COPY requirements.txt /app/requirements.txt
RUN pip install --upgrade pip \
    && pip install --index-url https://download.pytorch.org/whl/cpu torch \
    && pip install -r /app/requirements.txt \
    && pip install --no-deps dghs-imgutils

# Application code
COPY . /app/

# The model_to_prod folder is one level above this Dockerfile context.
# The ViT model is mounted at runtime via docker-compose to keep image size sane.

EXPOSE 8000

# Healthcheck hits the FastAPI /api/health endpoint
HEALTHCHECK --interval=30s --timeout=10s --start-period=120s --retries=3 \
    CMD curl -fsS http://localhost:8000/api/health || exit 1

# Use uvicorn directly (run.py would re-pip-install on every start)
CMD ["uvicorn", "app:app", "--host", "0.0.0.0", "--port", "8000"]
