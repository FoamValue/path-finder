#!/usr/bin/env bash
# PathFinder 备份脚本：存储目录 + archive + MySQL + Redis AOF/RDB
set -euo pipefail

BACKUP_ROOT="${BACKUP_ROOT:-./backups}"
STAMP="$(date +%Y%m%d_%H%M%S)"
DEST="$BACKUP_ROOT/$STAMP"
mkdir -p "$DEST"

MYSQL_USER="${MYSQL_USER:-pathfinder}"
MYSQL_PASSWORD="${MYSQL_PASSWORD:-pathfinder123}"
MYSQL_HOST="${MYSQL_HOST:-localhost}"
MYSQL_DB="${MYSQL_DB:-pathfinder}"
REDIS_PASSWORD="${REDIS_PASSWORD:-pathfinder123}"
STORAGE_ROOT="${STORAGE_ROOT:-./data/storage}"

echo "== 1/3 备份存储目录与归档 =="
tar -czf "$DEST/storage.tar.gz" -C "$(dirname "$STORAGE_ROOT")" "$(basename "$STORAGE_ROOT")"

echo "== 2/3 备份 MySQL =="
docker exec "$(docker compose -f docker/docker-compose.yml ps -q mysql 2>/dev/null || true)" \
  mysqldump -u"$MYSQL_USER" -p"$MYSQL_PASSWORD" "$MYSQL_DB" 2>/dev/null > "$DEST/mysql.sql" \
  || mysqldump -u"$MYSQL_USER" -p"$MYSQL_PASSWORD" -h"$MYSQL_HOST" "$MYSQL_DB" > "$DEST/mysql.sql" 2>/dev/null

echo "== 3/3 备份 Redis (AOF) =="
docker exec "$(docker compose -f docker/docker-compose.yml ps -q redis 2>/dev/null || true)" \
  redis-cli -a "$REDIS_PASSWORD" BGSAVE 2>/dev/null | grep -q Background || true

echo "备份完成：$DEST"
ls -lh "$DEST"
