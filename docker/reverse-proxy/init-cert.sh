#!/bin/bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

if [ ! -f "${SCRIPT_DIR}/.env" ]; then
  echo "Missing ${SCRIPT_DIR}/.env file" >&2
  exit 1
fi

# shellcheck disable=SC1090
source "${SCRIPT_DIR}/.env"

cd "${SCRIPT_DIR}"

docker compose build certbot

docker compose run --rm certbot \
  certbot certonly \
  --authenticator dns-porkbun \
  --dns-porkbun-credentials /etc/certbot-porkbun.ini \
  --dns-porkbun-propagation-seconds 60 \
  --email "${LETSENCRYPT_EMAIL}" \
  --agree-tos \
  --no-eff-email \
  -d "*.${DOMAIN}"
