#!/usr/bin/env bash
set -e

echo "Waiting for database..."
until pg_isready -h "${DB_HOST:-auth-db}" -p "${DB_PORT:-5432}" -U "${DB_USER:-auth_user}" -q 2>/dev/null; do
    sleep 1
done
echo "Database is ready."

echo "Running Alembic migrations..."
alembic upgrade head
echo "Migrations applied."

echo "Starting auth service..."
exec python -m app.main
