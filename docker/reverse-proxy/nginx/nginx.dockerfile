FROM alpine:3.22

RUN apk add --no-cache \
    inotify-tools \
    nginx \
    nginx-mod-http-headers-more

COPY watch-reload.sh /usr/local/bin/
RUN chmod +x /usr/local/bin/watch-reload.sh

CMD ["/bin/sh", "-c", "/usr/local/bin/watch-reload.sh & nginx -g 'daemon off;'"]
