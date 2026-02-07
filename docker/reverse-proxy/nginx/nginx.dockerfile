FROM alpine:3.22

RUN apk add --no-cache \
    inotify-tools \
    nginx \
    nginx-mod-http-headers-more

COPY reload-on-certificate-change.sh /usr/local/bin/
COPY entrypoint.sh /usr/local/bin/
RUN chmod +x /usr/local/bin/reload-on-certificate-change.sh /usr/local/bin/entrypoint.sh

ENTRYPOINT ["/usr/local/bin/entrypoint.sh"]
