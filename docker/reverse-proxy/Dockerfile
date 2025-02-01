FROM nginx:alpine

# Install required packages
RUN apk add --no-cache certbot certbot-nginx bash

# Copy configuration files
COPY nginx.conf /etc/nginx/nginx.conf
COPY default.conf.template /etc/nginx/templates/
COPY entrypoint.sh /

# Make the entrypoint script executable
RUN chmod +x /entrypoint.sh

# Create directory for Let's Encrypt
RUN mkdir -p /var/www/certbot

EXPOSE 80 443

ENTRYPOINT ["/entrypoint.sh"]