# Development

## Development mode

Run the backend and frontend locally from source, with PostgreSQL from Docker Compose.

### Prerequisites

- [Node.js & pnpm](https://pnpm.io/installation)
- [Scala 3](https://scala-lang.org/) & [sbt](https://www.scala-sbt.org/)
- [FFmpeg](https://ffmpeg.org/) and [ImageMagick](https://imagemagick.org/) (for media processing)

### 1. Prepare your media files

In dev mode the media files are expected in a directory named `media` inside the git repository. Move them there or
create a symbolic link.

### 2. Start the database

```bash
docker compose -f docker-compose.yml up -d postgres auth
```

`auth` (Dex) is optional: skip it if you run with `AMONY_AUTH_ENABLED=false`.

### 3. Start the backend

```bash
cd backend
sbt
```

Inside the sbt console run the command `run`

After compiling, the backend will be running on port `8182`. It will start scanning the `media` directory and log its
progress.

### 4. Start the frontend

```bash
cd frontend
fnm use # or nvm use
pnpm install
pnpm run generate # generate API client from OpenAPI spec
pnpm run dev
```

The frontend will be running on port `5173`. It will proxy all API requests to the backend on port `8182`.

### 5. Open your web browser

Navigate to `http://localhost:5173`

## Build a docker image

### Prerequisites

- [Node.js & pnpm](https://pnpm.io/installation)
- [Scala 3](https://scala-lang.org/) & [sbt](https://www.scala-sbt.org/)
- [Docker](https://www.docker.com/get-started)

### 1. Build the web client

```bash
cd frontend
fnm use # or nvm use
pnpm install
pnpm run generate
pnpm run build
```

### 2. Build the docker image

```bash
cd backend
sbt jibDockerBuild
```

### 3. Publishing images

Images are published to the GitHub Container Registry (GHCR) by the `Build and deploy` workflow
(`.github/workflows/build.yml`). They are public and live under `ghcr.io/merlijn/`:

- **`main`** — a merge that changes `backend/` or the build workflow publishes the moving `dev` tag
  (`ghcr.io/merlijn/amony-app:dev`). Frontend-only changes skip the backend build and reuse the existing image. A base
  image bump counts because the pin lives in `backend/build.sbt`.
- **Git tags** — pushing a tag (for example `v0.1.7`) publishes `latest` and the version tag (for example `0.1.7`).
- **Pull requests** — publish only when the head commit message contains `#publish`, under a per-PR tag (`pr-<number>`).

Runs are serialized per ref: `main` builds queue instead of overlapping, so two merges cannot race on the shared `dev`
tags (which could otherwise mix architectures or leave `dev` on an older commit). A pull request cancels its own stale
runs.

The Jib base image is built from `docker/base/Dockerfile` by the `Build base image` workflow
(`.github/workflows/base-image.yml`), which runs when that file changes and can also be started manually. Every build is
published as a UTC build timestamp tag (for example `2026.11.15.1423`) plus `latest`; pull requests only build the amd64
variant. `baseImageTag` in `backend/build.sbt` pins one of those tags, and Renovate opens a PR when a new build appears.

### 4. Deploying the demo

The public demo is redeployed by the `Deploy demo` workflow (`.github/workflows/deploy-demo.yml`). It runs automatically
once the `Build and deploy` workflow finishes successfully on `main`, and can also be triggered manually from the
Actions tab.

It connects to the server as `docker-user` and runs `deployment/deploy.sh`, the same script used for other deployments.
The script updates the checkout, pulls the new `dev` image and recreates the containers with:

```bash
docker compose -f docker-compose.yml -f docker-compose-https.yml -f docker-compose.demo.yml \
  --profile zitadel up -d --build
```

The web client is not part of the application image. The `Build and deploy` workflow publishes it to GHCR as an OCI
artifact (`ghcr.io/<owner>/amony-web:fe-<tree>`), where the tag is the git tree hash of `frontend/`. The deploy pulls
the artifact that matches its own checkout, unpacks it into `web/releases/<tree>` and repoints the `web/current`
symlink. The `web` service in `docker-compose.yml` serves that directory, and the reverse proxy routes `/` to it.

The frontend build emits pre-compressed `.gz` files; the `web` service serves those with `gzip_static` when the client
accepts gzip. Brotli is not generated: the stock nginx image has no brotli module, so the files could not be served.

Non-secret configuration lives in `deployment/demo.env`. The sensitive values (database password, JWT secret, Dex client
secrets / admin password hash, Porkbun credentials) plus the SSH deploy key and host details are stored in the
repository's `demo` GitHub Environment.

### 5. Deploying behind an existing reverse proxy

A host that already runs a shared reverse proxy can use the same layout. Attach the `amony` and `web` services to the
proxy's external network in a local override:

```yaml
services:
  amony:
    networks:
      - default
      - https-proxy
  web:
    networks:
      - default
      - https-proxy

networks:
  https-proxy:
    external: true
```

Run `deployment/deploy.sh` with the overlays for that host:

```bash
./deployment/deploy.sh -f docker-compose.yml -f docker-compose.override.yml
```

The shared proxy should send `/api/` to `amony-app:8182` and everything else to `amony-web:80`. Match its
`client_max_body_size` and timeouts to the demo proxy (`15g` and `1200s`) so uploads and long-running requests are not
cut off.

Back to the [main README](../README.md).
