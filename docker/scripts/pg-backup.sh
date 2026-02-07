#!/bin/sh
set -e

# Configuration from environment variables
BACKUP_DIR="${BACKUP_DIR:-/backups}"
BACKUP_RETENTION_DAYS="${BACKUP_RETENTION_DAYS:-7}"
TIMESTAMP=$(date +%Y%m%d_%H%M%S)
BACKUP_FILE="${BACKUP_DIR}/amony_${TIMESTAMP}.sql.gz"

echo "[$(date '+%Y-%m-%d %H:%M:%S')] Starting PostgreSQL backup..."

# Create backup directory if it doesn't exist
mkdir -p "${BACKUP_DIR}"

# Create the backup using pg_dump and compress it
PGPASSWORD="${DATABASE_PASSWORD}" pg_dump \
    -h "${DATABASE_HOST:-postgres}" \
    -U "${DATABASE_USERNAME:-postgres}" \
    -d "${DATABASE_NAME:-amony}" \
    --no-owner \
    --no-acl \
    | gzip > "${BACKUP_FILE}"

# Check if backup was successful
if [ -f "${BACKUP_FILE}" ] && [ -s "${BACKUP_FILE}" ]; then
    BACKUP_SIZE=$(du -h "${BACKUP_FILE}" | cut -f1)
    echo "[$(date '+%Y-%m-%d %H:%M:%S')] Backup completed successfully: ${BACKUP_FILE} (${BACKUP_SIZE})"
else
    echo "[$(date '+%Y-%m-%d %H:%M:%S')] ERROR: Backup failed or file is empty"
    exit 1
fi

# Remove old backups
if [ "${BACKUP_RETENTION_DAYS}" -gt 0 ]; then
    echo "[$(date '+%Y-%m-%d %H:%M:%S')] Removing backups older than ${BACKUP_RETENTION_DAYS} days..."
    find "${BACKUP_DIR}" -name "amony_*.sql.gz" -type f -mtime +${BACKUP_RETENTION_DAYS} -delete
    REMAINING=$(ls -1 "${BACKUP_DIR}"/amony_*.sql.gz 2>/dev/null | wc -l | tr -d ' ')
    echo "[$(date '+%Y-%m-%d %H:%M:%S')] Cleanup complete. ${REMAINING} backup(s) remaining."
fi

echo "[$(date '+%Y-%m-%d %H:%M:%S')] Backup process finished."
