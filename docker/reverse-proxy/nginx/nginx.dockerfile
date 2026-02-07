FROM alpine:3.22

RUN apk add --no-cache \
    inotify-tools \
    nginx \
    nginx-mod-http-headers-more

COPY reload-on-certificate-change.sh /usr/local/bin/
RUN chmod +x /usr/local/bin/reload-on-certificate-change.sh

CMD ["/bin/sh", "-c", "/usr/local/bin/reload-on-certificate-change.sh & nginx -g 'daemon off;'"]
