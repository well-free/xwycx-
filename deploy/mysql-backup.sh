#!/usr/bin/env bash
set -euo pipefail

BACKUP_DIR="${BACKUP_DIR:-/var/backups/xwycx}"
DB_NAME="${XWYCX_DB_NAME:-xwycx_order}"
DB_USER="${XWYCX_DB_USERNAME:?XWYCX_DB_USERNAME is required}"
DB_PASSWORD="${XWYCX_DB_PASSWORD:?XWYCX_DB_PASSWORD is required}"
STAMP="$(date +%Y%m%d-%H%M%S)"

mkdir -p "$BACKUP_DIR"
mysqldump -u"$DB_USER" -p"$DB_PASSWORD" --single-transaction --routines --triggers "$DB_NAME" | gzip > "$BACKUP_DIR/$DB_NAME-$STAMP.sql.gz"
find "$BACKUP_DIR" -name "$DB_NAME-*.sql.gz" -mtime +7 -delete
