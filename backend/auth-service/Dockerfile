FROM python:3.12-slim

WORKDIR /app

RUN apt-get update && apt-get install -y --no-install-recommends \
    build-essential \
    libpq-dev \
    postgresql-client \
    curl \
    netcat-openbsd \
    && rm -rf /var/lib/apt/lists/*

COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

COPY . .

# ── Protobuf generation ───────────────────────────────────────────────
RUN mkdir -p app/generated && \
    python -m grpc_tools.protoc \
        -I./proto \
        --python_out=./app/generated \
        --grpc_python_out=./app/generated \
        ./proto/auth.proto

RUN python - <<'EOF'
import re, pathlib
target = pathlib.Path("app/generated/auth_pb2_grpc.py")
text = target.read_text()
text = re.sub(r'^import auth_pb2', 'from . import auth_pb2', text, flags=re.MULTILINE)
target.write_text(text)
print("Patched", target)
EOF

RUN touch app/__init__.py app/generated/__init__.py

# ── Entrypoint: wait for DB → run migrations → start server ──────────
COPY entrypoint.sh /entrypoint.sh
RUN sed -i 's/\r//' /entrypoint.sh && chmod +x /entrypoint.sh

EXPOSE 50051

HEALTHCHECK --interval=30s --timeout=10s --start-period=20s --retries=3 \
    CMD python -c "\
import asyncio, grpc.aio; \
async def check(): \
    channel = grpc.aio.insecure_channel('localhost:50051'); \
    await channel.channel_ready(); \
    await channel.close(); \
asyncio.run(check())" || exit 1

ENTRYPOINT ["/entrypoint.sh"]
