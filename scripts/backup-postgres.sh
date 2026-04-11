#!/usr/bin/env bash
# Бэкап PostgreSQL из контейнера сервиса db (docker-compose.yml).
#
# Запуск из корня репозитория на VPS:
#   ./scripts/backup-postgres.sh
#
# Переменные (опционально):
#   BACKUP_DIR      — каталог для .sql.gz (по умолчанию: ./backups рядом с compose)
#   RETENTION_DAYS  — удалять дампы старше N дней (по умолчанию: 14; 0 = не удалять)
#
# Cron (ежедневно в 03:15 UTC), пользователь с правом на docker:
#   15 3 * * * cd /path/to/Config-manager-bot-v2 && ./scripts/backup-postgres.sh >>/var/log/pg-backup.log 2>&1
#
# Восстановление (осторожно: перезапишет объекты из дампа):
#   gunzip -c backups/pg_YYYYMMDD_HHMMSS.sql.gz | docker compose exec -T db sh -c 'psql -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB"'

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
cd "$REPO_ROOT"

BACKUP_DIR="${BACKUP_DIR:-$REPO_ROOT/backups}"
RETENTION_DAYS="${RETENTION_DAYS:-14}"

mkdir -p "$BACKUP_DIR"

STAMP="$(date -u +%Y%m%d_%H%M%S)"
OUT="$BACKUP_DIR/pg_${STAMP}.sql.gz"

# Учётные данные берутся из окружения контейнера (POSTGRES_* из compose / .env).
docker compose exec -T db sh -c \
  'pg_dump --clean --if-exists -U "$POSTGRES_USER" -d "$POSTGRES_DB"' \
  | gzip -c >"$OUT"

echo "OK: $OUT ($(du -h "$OUT" | cut -f1))"

if [[ "$RETENTION_DAYS" =~ ^[0-9]+$ ]] && (( RETENTION_DAYS > 0 )); then
  find "$BACKUP_DIR" -maxdepth 1 -type f -name 'pg_*.sql.gz' -mtime "+${RETENTION_DAYS}" -delete
fi
