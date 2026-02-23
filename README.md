# Amony

A self-hosted media library web application. It scans a local directory for media files and lets you browse, organize and view them in a web browser.

The server should work well for personal use up to 100k media files, depending on your hardware. Beyond that is untested.

A live demo is available at [https://demo.amony.app](https://demo.amony.app). It is running on a single [Hetzner](https://www.hetzner.com/) Cost-Optimized server (~$4/month).

![](docs/app-screenshot.png)

**Note:** All videos on the demo site are free (public domain) and sourced from [Pexels](https://www.pexels.com/license/)
## Features

- Scans local directory for media files (video, audio, images, etc...)
- Search and filter media by name, tags and more
- Organize media with tags *
- Upload media files through the web interface *
- Delete media files (with confirmation) *
- optional: Oauth2/OIDC authentication with [Dex](https://github.com/dexidp/dex) (or an oauth provider of your choice)
- optional: Https with automatic certificate management via Let's Encrypt (when using the provided Docker Compose setup)
- optional: Automatic database backups using a docker compose profile (with recovery mechanism)

*) These features are locked behind a login, this can be completely disabled by setting `AMONY_AUTH_ENABLED=false` in the environment variables.

# How to use

## Docker Compose

### Prerequisites
- [Docker](https://www.docker.com/get-started) and [Docker Compose](https://docs.docker.com/compose/)

### 1. Prepare your environment

Copy the `.env.example` file to `.env` and edit the environment variables as needed. At a minimum, you should set `AMONY_HOST_MEDIA_PATH` to the path of your media files on the host machine. 
It is recommended to change all credentials (like `DATABASE_PASSWORD`) to secure random values.

As mentioned before, you can disable authentication completely by setting `AMONY_AUTH_ENABLED=false`. Otherwise, the default credentials for the Dex oauth server are:
- Username: `admin@amony.example`
- Password: `password`

### 2. Run with Docker Compose

```bash
docker compose up -d
```

This starts the application along with a PostgreSQL database and a [Dex](https://github.com/dexidp/dex) oauth server. The app will be available at http://localhost:8182.

You can mount a local directory containing your media files by editing the `docker-compose.yml` volumes for the `amony` service.

**Notes:**
- It might take some time to process the videos on first startup. Check progress with `docker compose logs amony`
- For HTTPS with automatic certificate management, see `docker-compose-https.yml`

## Development mode

### Prerequisites
- [Node.js & npm](https://docs.npmjs.com/downloading-and-installing-node-js-and-npm)
- [Scala 3](https://scala-lang.org/) & [sbt](https://www.scala-sbt.org/)
- [FFmpeg](https://ffmpeg.org/) and [ImageMagick](https://imagemagick.org/) (for media processing)

### 1. Prepare your media files

In dev mode the media files are expected in a directory named `media` inside the git repository. Move them there or create a symbolic link.

### 2. Start the database

```bash
docker compose -f docker-compose.yml up -d postgres
```

### 3. Start the backend
```bash
cd backend
sbt
```

Inside the sbt console run the command `run`

After compiling, the backend will be running on port `8182`. It will start scanning the `media` directory and log its progress.

### 4. Start the frontend
```bash
cd frontend
nvm use # or fnm use
npm install
npm run generate # generate API client from OpenAPI spec
npm run dev
```

The frontend will be running on port `5173`. It will proxy all API requests to the backend on port `8182`.

### 5. Open your web browser

Navigate to `http://localhost:5173`

## Build a docker image

### Prerequisites

- [Node.js & npm](https://docs.npmjs.com/downloading-and-installing-node-js-and-npm)
- [Scala 3](https://scala-lang.org/) & [sbt](https://www.scala-sbt.org/)
- [Docker](https://www.docker.com/get-started)

### 1. Build the web client

```bash
cd frontend
nvm use # or fnm use
npm install
npm run generate
npm run build
```

### 2. Build the docker image

```bash
cd backend
sbt jibDockerBuild
```

## Tech stack

| Layer | Technology |
|---|---|
| Backend | Scala 3, Cats Effect, http4s, Tapir, Skunk |
| Frontend | TypeScript, React, Vite |
| Database | PostgreSQL |
| Search | Embedded Apache Solr |
| Auth | JWT + OAuth2/OIDC (Dex) |
| Media processing | FFmpeg, ImageMagick |
| Infrastructure | Docker Compose, Nginx, Let's Encrypt |
