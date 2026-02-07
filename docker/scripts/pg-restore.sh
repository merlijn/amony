#!/bin/bash
set -e

RESTORE_FILE="${RESTORE_FILE}"
BACKUP_DIR="${BACKUP_DIR:-/backups}"

echo "[$(date '+%Y-%m-%d %H:%M:%S')] PostgreSQL Restore starting..."

if [ -z "${RESTORE_FILE}" ]; then
    echo "[$(date '+%Y-%m-%d %H:%M:%S')] ERROR: RESTORE_FILE environment variable is not set"
    exit 1
fi

RESTORE_PATH="${BACKUP_DIR}/${RESTORE_FILE}"

if [ ! -f "${RESTORE_PATH}" ]; then
    echo "[$(date '+%Y-%m-%d %H:%M:%S')] ERROR: Backup file not found: ${RESTORE_PATH}"
    exit 1
fi

echo "[$(date '+%Y-%m-%d %H:%M:%S')] Restoring from: ${RESTORE_PATH}"

# Drop and recreate the database to ensure clean restore
echo "[$(date '+%Y-%m-%d %H:%M:%S')] Dropping existing database..."
PGPASSWORD="${DATABASE_PASSWORD}" dropdb \
    -h "${DATABASE_HOST:-postgres}" \
    -U "${DATABASE_USERNAME:-postgres}" \
    --if-exists \
    "${DATABASE_NAME:-amony}"

echo "[$(date '+%Y-%m-%d %H:%M:%S')] Creating fresh database..."
PGPASSWORD="${DATABASE_PASSWORD}" createdb \
    -h "${DATABASE_HOST:-postgres}" \
    -U "${DATABASE_USERNAME:-postgres}" \
    "${DATABASE_NAME:-amony}"

# Restore based on file extension
echo "[$(date '+%Y-%m-%d %H:%M:%S')] Restoring data..."
if [[ "${RESTORE_FILE}" == *.gz ]]; then
    gunzip -c "${RESTORE_PATH}" | PGPASSWORD="${DATABASE_PASSWORD}" psql \
        -h "${DATABASE_HOST:-postgres}" \
        -U "${DATABASE_USERNAME:-postgres}" \
        -d "${DATABASE_NAME:-amony}" \
        --quiet
else
    PGPASSWORD="${DATABASE_PASSWORD}" psql \
        -h "${DATABASE_HOST:-postgres}" \
        -U "${DATABASE_USERNAME:-postgres}" \
        -d "${DATABASE_NAME:-amony}" \
        --quiet \
        < "${RESTORE_PATH}"
fi

echo "[$(date '+%Y-%m-%d %H:%M:%S')] Restore completed successfully!"
