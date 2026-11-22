FROM nginx:1.30.7-alpine@sha256:caf4e1e032a20f9c3c5e28fc7f25ffd768ad8677b4975c54193075997b3a97e3

COPY docker/web/nginx.conf /etc/nginx/conf.d/default.conf
COPY frontend/dist /srv/web/current
