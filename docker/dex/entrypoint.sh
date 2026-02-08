#!/bin/sh
set -e

# Substitute only our named variables, leaving bcrypt $2a$... hashes intact
envsubst '${DOMAIN_TLD} ${DOMAIN_SUB_AUTH} ${DEX_ADMIN_EMAIL} ${DEX_ADMIN_USERNAME} ${DEX_ADMIN_PASSWORD_HASH}' \
  < /etc/dex/config.yaml.template > /tmp/config.yaml

exec dex serve /tmp/config.yaml
