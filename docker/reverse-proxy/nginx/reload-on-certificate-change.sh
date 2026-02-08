#!/bin/sh

while true; do
    echo "Watching /etc/letsencrypt for changes..."
    # Wait for any file changes in /etc/letsencrypt
    inotifywait -e create,modify,moved_to /etc/letsencrypt/ -r 2>/dev/null

    echo "Certificate change detected, reloading Nginx in 3 seconds..."
    sleep 3

    # Test config before reloading
    if nginx -t 2>/dev/null; then
        echo "Configuration valid, reloading..."
        nginx -s reload
        echo "Nginx reloaded successfully"
    else
        echo "Configuration test failed, skipping reload"
    fi

    # Prevent rapid reloads
    echo "Waiting 10s before next check..."
    sleep 10
done