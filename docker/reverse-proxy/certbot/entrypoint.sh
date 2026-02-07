#!/bin/sh
set -e
echo "dns_porkbun_key = ${PORKBUN_API_KEY}" > /etc/certbot-porkbun.ini
echo "dns_porkbun_secret = ${PORKBUN_SECRET_KEY}" >> /etc/certbot-porkbun.ini
chmod 600 /etc/certbot-porkbun.ini
exec "$@"
