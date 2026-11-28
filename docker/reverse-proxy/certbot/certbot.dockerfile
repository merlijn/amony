FROM python:3.11-slim@sha256:7032841967d07aa3353905945dc186901c89b6b319c61425f4439f677a4a8a34

RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

RUN pip install --no-cache-dir \
    certbot \
    certbot-dns-porkbun

COPY entrypoint.sh /entrypoint.sh

ENTRYPOINT ["/entrypoint.sh"]
CMD ["certbot"]
