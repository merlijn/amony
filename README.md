# Amony

A self-hosted media library web application. It scans a local directory for media files and lets you browse, search, and play them in a web browser.

Example screenshot:

![](docs/app-screenshot.png)

A live demo is available at [https://demo.amony.app](https://demo.amony.app). It is running on a [Hetzner](https://www.hetzner.com/) shared VM (~$4/month).

**Note:** All videos on the demo site are free (public domain) and sourced from [Pexels](https://www.pexels.com/license/)

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

# How to use

## Docker Compose

### Prerequisites
- [Docker](https://www.docker.com/get-started) with [Compose](https://docs.docker.com/compose/)

### 1. Prepare your environment

Copy the `.env.example` file to `.env` and edit the environment variables as needed. At a minimum, you should set `AMONY_HOST_MEDIA_PATH` to the path of your media files on the host machine.


### 1. Run with Docker Compose

```bash
docker compose up
```

This starts the application along with a PostgreSQL database. The app will be available at http://localhost:8182.

You can mount a local directory containing your media files by editing the `docker-compose.yml` volumes for the `amony` service.

**Notes:**
- It might take some time to process the videos on first startup. Check progress with `docker compose logs amony`
- For HTTPS with automatic certificate management, see `docker-compose-https.yml`

## Development mode

### Prerequisites
- [Node.js & npm](https://docs.npmjs.com/downloading-and-installing-node-js-and-npm)
- [Scala 3](https://scala-lang.org/) & [sbt](https://www.scala-sbt.org/)
- [FFmpeg](https://ffmpeg.org/)
- A running PostgreSQL instance

### 1. Prepare your media files

In dev mode the media files are expected in a directory named `media` inside the git repository. Move them there or create a symbolic link.

### 2. Start the backend
```bash
cd backend
sbt
```

Inside the sbt console run the command `~reStart`

After compiling, the backend will be running on port `8080`. It will start scanning the `media` directory and log its progress.

### 3. Start the frontend
```bash
cd frontend
npm install
npm start
```

After compiling, the frontend will be running on port `3000`. It will proxy all API requests to the backend on port `8080`.

### 4. Open your web browser

Navigate to `http://localhost:3000`

## Build a docker image

### Prerequisites

- [Node.js & npm](https://docs.npmjs.com/downloading-and-installing-node-js-and-npm)
- [Scala 3](https://scala-lang.org/) & [sbt](https://www.scala-sbt.org/)
- [Docker](https://www.docker.com/get-started)

### 1. Build the web client

```bash
cd frontend
npm install
npm run build
```

### 2. Build the docker image

```bash
cd backend
sbt jibDockerBuild
```
