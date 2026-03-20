FROM python:3.12-slim
WORKDIR /app

RUN apt-get update && apt-get install -y --no-install-recommends \
    build-essential \
    libpq-dev \
    curl \
    netcat-openbsd \
    && rm -rf /var/lib/apt/lists/*

# Зависимости отдельным слоем для кэша
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

# Копируем весь проект
COPY . .

RUN mkdir -p app/infrastructure/grpc/generated

RUN python -m grpc_tools.protoc \
        -I./proto \
        --python_out=./app/infrastructure/grpc/generated \
        --grpc_python_out=./app/infrastructure/grpc/generated \
        ./proto/auth.proto \
        ./proto/user.proto

RUN echo "=== GENERATED FILES ===" && \
    ls -la app/infrastructure/grpc/generated/ && \
    echo "=== __init__.py CONTENT ===" && \
    cat app/infrastructure/grpc/generated/__init__.py && \
    echo "=== auth_pb2.py EXISTS ===" && \
    python -c "import sys; sys.path.insert(0, '.'); from app.infrastructure.grpc.generated import auth_pb2; print('OK')" 2>&1


RUN test -f app/infrastructure/grpc/generated/auth_pb2.py || (echo "ERROR: auth_pb2.py not generated!" && exit 1)

RUN python - <<'EOF'
import re, pathlib
for name in ["auth_pb2_grpc", "user_pb2_grpc"]:
    target = pathlib.Path(f"app/infrastructure/grpc/generated/{name}.py")
    text = target.read_text()
    text = re.sub(r"^import (\w+pb2)", r"from . import \1", text, flags=re.MULTILINE)
    target.write_text(text)
    print("Patched", target)
EOF

# __init__.py создаём ПОСЛЕДНИМ — после всей генерации
RUN find app -type d | xargs -I{} touch {}/__init__.py 2>/dev/null || true


EXPOSE 8000

HEALTHCHECK --interval=30s --timeout=10s --start-period=20s --retries=3 \
    CMD python -c "import httpx; exit(0 if httpx.get('http://localhost:8000/health').status_code == 200 else 1)" || exit 1

CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8000", "--loop", "uvloop"]
