FROM python:3.12-slim

WORKDIR /app

RUN apt-get update && apt-get install -y --no-install-recommends \
    build-essential \
    libpq-dev \
    curl \
    netcat-openbsd \
    && rm -rf /var/lib/apt/lists/*

COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

COPY . .

RUN mkdir -p app/infrastructure/grpc/generated && \
    python -m grpc_tools.protoc \
        -I./proto \
        --python_out=./app/infrastructure/grpc/generated \
        --grpc_python_out=./app/infrastructure/grpc/generated \
        ./proto/auth.proto

RUN python - <<'EOF'
import re, pathlib

target = pathlib.Path("app/infrastructure/grpc/generated/auth_pb2_grpc.py")
text = target.read_text()
# Заменяем любой абсолютный "import auth_pb2" на относительный
text = re.sub(r'^import (\w+_pb2)', r'from . import \1', text, flags=re.MULTILINE)
target.write_text(text)
print("Patched:", target)
EOF

RUN touch app/__init__.py \
         app/api/__init__.py \
         app/api/v1/__init__.py \
         app/api/v1/auth/__init__.py \
         app/core/__init__.py \
         app/core/interfaces/__init__.py \
         app/core/use_cases/__init__.py \
         app/core/use_cases/auth/__init__.py \
         app/infrastructure/__init__.py \
         app/infrastructure/grpc/__init__.py \
         app/infrastructure/grpc/generated/__init__.py \
         app/exceptions/__init__.py

EXPOSE 8000

HEALTHCHECK --interval=30s --timeout=10s --start-period=20s --retries=3 \
    CMD python -c "import httpx; exit(0 if httpx.get('http://localhost:8000/health').status_code == 200 else 1)" || exit 1

CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8000", "--loop", "uvloop"]