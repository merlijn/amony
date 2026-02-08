#!/bin/sh
set -e

# Process nginx config templates with environment variable substitution
for template in /etc/nginx/templates/*.template; do
    if [ -f "$template" ]; then
        filename=$(basename "$template" .template)
        envsubst '${DOMAIN_TLD} ${DOMAIN_SUB_AUTH} ${DOMAIN_SUB_AMONY}' < "$template" > "/etc/nginx/$filename"
        echo "Generated /etc/nginx/$filename from $template"
    fi
done

# Start the certificate reload watcher in background and nginx in foreground
exec /bin/sh -c "/usr/local/bin/reload-on-certificate-change.sh & nginx -g 'daemon off;'"
