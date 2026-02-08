FROM dexidp/dex:latest

USER root
RUN apk add --no-cache gettext
USER 1001:1001

COPY entrypoint.sh /usr/local/bin/entrypoint.sh

ENTRYPOINT ["/usr/local/bin/entrypoint.sh"]
