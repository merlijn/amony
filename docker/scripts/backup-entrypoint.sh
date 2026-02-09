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

# Write environment variables to a file so cron jobs can source them
ENV_FILE="/etc/backup-env.sh"
cat > "${ENV_FILE}" <<EOF
export DATABASE_HOST="${DATABASE_HOST:-postgres}"
export DATABASE_USERNAME="${DATABASE_USERNAME:-postgres}"
export DATABASE_PASSWORD="${DATABASE_PASSWORD}"
export DATABASE_NAME="${DATABASE_NAME:-amony}"
export BACKUP_DIR="${BACKUP_DIR:-/backups}"
export BACKUP_RETENTION_DAYS="${BACKUP_RETENTION_DAYS:-7}"
EOF
chmod 600 "${ENV_FILE}"

# Create cron job that sources the environment file before running the backup
echo "${BACKUP_SCHEDULE} . ${ENV_FILE} && /scripts/pg-backup.sh >> /var/log/backup.log 2>&1" | crontab -

echo "[$(date '+%Y-%m-%d %H:%M:%S')] Cron job configured:"
crontab -l

# Run initial backup if BACKUP_ON_STARTUP is set
if [ "${BACKUP_ON_STARTUP:-false}" = "true" ]; then
    echo "[$(date '+%Y-%m-%d %H:%M:%S')] Running initial backup..."
    /scripts/pg-backup.sh || echo "[$(date '+%Y-%m-%d %H:%M:%S')] Initial backup failed, continuing anyway..."
fi

echo "[$(date '+%Y-%m-%d %H:%M:%S')] Starting cron in foreground..."

# Start cron in foreground
cron -f
