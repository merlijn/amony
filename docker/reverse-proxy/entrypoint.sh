#!/bin/bash

# Replace environment variables in nginx config
envsubst '${DOMAIN} ${SERVER}' < /etc/nginx/templates/default.conf.template > /etc/nginx/conf.d/default.conf

# Check if certificates already exist
if [ ! -d "/etc/letsencrypt/live/${DOMAIN}" ]; then
    # Get certificates for the first time
    certbot certonly --nginx --non-interactive --agree-tos -m admin@${DOMAIN} -d ${DOMAIN}
fi

# Start certificate renewal cron job
(
    while :
    do
        certbot renew --nginx
        sleep 12h
    done
) &

# Start nginx
nginx -g 'daemon off;'