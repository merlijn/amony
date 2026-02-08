#!/bin/bash
set -e

BACKUP_SCHEDULE="${BACKUP_SCHEDULE:-0 2 * * *}"

echo "[$(date '+%Y-%m-%d %H:%M:%S')] PostgreSQL Backup Container starting..."
echo "[$(date '+%Y-%m-%d %H:%M:%S')] Backup schedule: ${BACKUP_SCHEDULE}"
echo "[$(date '+%Y-%m-%d %H:%M:%S')] Backup retention: ${BACKUP_RETENTION_DAYS:-7} days"
echo "[$(date '+%Y-%m-%d %H:%M:%S')] Backup directory: ${BACKUP_DIR:-/backups}"

# Install cron
echo "[$(date '+%Y-%m-%d %H:%M:%S')] Installing cron..."
apt-get update -qq && apt-get install -y -qq cron > /dev/null

# Create cron job with environment variables
echo "${BACKUP_SCHEDULE} DATABASE_HOST=${DATABASE_HOST:-postgres} DATABASE_USERNAME=${DATABASE_USERNAME:-postgres} DATABASE_PASSWORD=${DATABASE_PASSWORD} DATABASE_NAME=${DATABASE_NAME:-amony} BACKUP_DIR=${BACKUP_DIR:-/backups} BACKUP_RETENTION_DAYS=${BACKUP_RETENTION_DAYS:-7} /scripts/pg-backup.sh >> /var/log/backup.log 2>&1" | crontab -

echo "[$(date '+%Y-%m-%d %H:%M:%S')] Cron job configured:"
crontab -l | sed "s/DATABASE_PASSWORD=[^ ]*/DATABASE_PASSWORD=***/"

# Run initial backup if BACKUP_ON_STARTUP is set
if [ "${BACKUP_ON_STARTUP:-false}" = "true" ]; then
    echo "[$(date '+%Y-%m-%d %H:%M:%S')] Running initial backup..."
    /scripts/pg-backup.sh || echo "[$(date '+%Y-%m-%d %H:%M:%S')] Initial backup failed, continuing anyway..."
fi

echo "[$(date '+%Y-%m-%d %H:%M:%S')] Starting cron in foreground..."

# Start cron in foreground
cron -f
