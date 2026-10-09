# Amony

A self-hosted media server application with a simple web UI. It scans a local directory for media files and lets you
browse, organize and view them in a web browser.

The server should work well for personal use up to 100k media files, depending on your hardware. Beyond that is
untested.

![](docs/app-screenshot.png)

A live demo is available at [https://demo.amony.app](https://demo.amony.app). It is running on a single
[Hetzner](https://www.hetzner.com/) Cost-Optimized server (~$4/month).

**Note:** All videos on the demo site are free (public domain) and sourced from
[Pexels](https://www.pexels.com/license/)

## Features

- Scans local directory for media files (video, audio, images, etc...)
- Search and filter media by name, tags and more
- Organize media with tags *
- Upload media files through the web interface *
- Delete media files (with confirmation) *
- optional: Oauth2/OIDC authentication with [Dex](https://github.com/dexidp/dex) or [Zitadel](https://zitadel.com) (or
  an identity provider of your choice), including roles read from a configurable OIDC claim
- optional: Https with automatic certificate management via Let's Encrypt (when using the provided Docker Compose setup)
- optional: Automatic database backups using a docker compose profile (with recovery mechanism)

*) These features are locked behind a login. The login itself can be completely disabled by setting
`AMONY_AUTH_ENABLED=false` in the environment variables. To require login for all access (no anonymous browsing) set
`AMONY_AUTH_REQUIRE_LOGIN=true`.

# How to use

## Docker Compose

### Prerequisites

- [Docker](https://www.docker.com/get-started) and [Docker Compose](https://docs.docker.com/compose/)

### 1. Prepare your environment

Copy the `.env.example` file to `.env` and edit the environment variables as needed. At a minimum, you should set
`AMONY_HOST_MEDIA_PATH` to the path of your media files on the host machine. It is recommended to change all credentials
(like `DATABASE_PASSWORD`) to secure random values.

On first startup a default media source (bucket) pointing at the mounted media directory is stored in the database. It
can be tuned with the `AMONY_DEFAULT_MEDIA_PATH`, `AMONY_DEFAULT_MEDIA_GENERATE_PREVIEWS_ON_ADD`,
`AMONY_DEFAULT_MEDIA_SYNC_ON_STARTUP` and `AMONY_DEFAULT_MEDIA_SCAN_ENABLED` variables. After that, these variables are
ignored and buckets are managed by an admin through the API (`/api/admin/buckets`).

As mentioned before, you can disable authentication completely by setting `AMONY_AUTH_ENABLED=false`. Otherwise, the
default credentials for the Dex oauth server are:

- Username: `admin@amony.example`
- Password: `password`

### 2. Run with Docker Compose

```bash
docker compose --profile dex up -d
```

This starts the application along with a PostgreSQL database and the bundled [Dex](https://github.com/dexidp/dex) oauth
server. The app will be available at http://localhost:8182.

An optional `zitadel` profile runs [Zitadel](https://zitadel.com) instead, for trialling a full identity provider
(self-registration, MFA, roles). See the Zitadel section in `.env.example`.

You can mount a local directory containing your media files by editing the `docker-compose.yml` volumes for the `amony`
service.

**Notes:**

- It might take some time to process the videos on first startup. Check progress with `docker compose logs amony`
- For HTTPS with automatic certificate management, see `docker-compose-https.yml`

## Development

See [docs/development.md](docs/development.md) for running from source, building and publishing the Docker image, and
deploying the demo.

## Tech stack

| Layer            | Technology                                 |
| ---------------- | ------------------------------------------ |
| Backend          | Scala 3, Cats Effect, http4s, Tapir, Skunk |
| Frontend         | TypeScript, React, Vite                    |
| Database         | PostgreSQL                                 |
| Search           | Embedded Apache Solr                       |
| Auth             | JWT + OAuth2/OIDC (Dex)                    |
| Media processing | FFmpeg, ImageMagick                        |
| Infrastructure   | Docker Compose, Nginx, Let's Encrypt       |
