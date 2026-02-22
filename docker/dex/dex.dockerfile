FROM dexidp/dex:latest

USER root
RUN apk add --no-cache gettext

COPY entrypoint.sh /usr/local/bin/entrypoint.sh
RUN chmod +x /usr/local/bin/entrypoint.sh

USER 1001:1001

ENTRYPOINT ["/usr/local/bin/entrypoint.sh"]
