FROM python:3.12-slim

WORKDIR /app

RUN apt-get update && apt-get install -y --no-install-recommends \
    build-essential \
    libpq-dev \
    postgresql-client \
    && rm -rf /var/lib/apt/lists/*

COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

COPY . .

RUN find . -type d -exec test -f {}/__init__.py \; -o -exec touch {}/__init__.py \;

EXPOSE 50051

HEALTHCHECK --interval=30s --timeout=10s --start-period=10s --retries=3 \
    CMD python -c "\
import asyncio; \
from sqlalchemy import text; \
from app.database.session import get_session; \
async def check(): \
    async with get_session() as s: \
        await s.execute(text('SELECT 1')); \
asyncio.run(check())" || exit 1

CMD ["python", "-m", "app.main"]