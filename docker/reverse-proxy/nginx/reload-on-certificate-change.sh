#!/bin/sh
# Mounted into /docker-entrypoint.d of the stock nginx image. The loop is backgrounded so it
# does not block the image entrypoint, which starts nginx once all scripts have returned.

(
    cert="/etc/letsencrypt/live/${DOMAIN_TLD}/fullchain.pem"
    last=""

    while :; do
        now=$(stat -c %Y "$cert" 2>/dev/null || echo "")
        if [ -n "$now" ] && [ "$now" != "$last" ]; then
            if [ -n "$last" ] && nginx -t; then
                nginx -s reload
            fi
            last="$now"
        fi
        sleep 300
    done
) &
