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

RUN mkdir -p app/generated && \
    python -m grpc_tools.protoc \
        -I./proto \
        --python_out=./app/generated \
        --grpc_python_out=./app/generated \
        ./proto/messaging.proto \
        ./proto/auth.proto \
        ./proto/contacts.proto \
        ./proto/media.proto

RUN python - <<'EOF'
import re, pathlib

for name in ["messaging", "auth", "contacts", "media"]:
    grpc_file = pathlib.Path(f"app/generated/{name}_pb2_grpc.py")
    if grpc_file.exists():
        text = grpc_file.read_text()
        text = re.sub(
            rf'^import {name}_pb2',
            f'from . import {name}_pb2',
            text,
            flags=re.MULTILINE,
        )
        grpc_file.write_text(text)
        print(f"Patched {grpc_file}")
EOF

RUN touch app/__init__.py app/generated/__init__.py

EXPOSE 50054

HEALTHCHECK --interval=30s --timeout=10s --start-period=20s --retries=3 \
    CMD python -c "\
import asyncio, grpc.aio; \
async def check(): \
    channel = grpc.aio.insecure_channel('localhost:50054'); \
    await channel.channel_ready(); \
    await channel.close(); \
asyncio.run(check())" || exit 1

CMD ["python", "-m", "app.main"]
