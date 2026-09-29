# Development mode

Run the backend and frontend locally from source, with PostgreSQL from Docker Compose.

## Prerequisites
- [Node.js & pnpm](https://pnpm.io/installation)
- [Scala 3](https://scala-lang.org/) & [sbt](https://www.scala-sbt.org/)
- [FFmpeg](https://ffmpeg.org/) and [ImageMagick](https://imagemagick.org/) (for media processing)

## 1. Prepare your media files

In dev mode the media files are expected in a directory named `media` inside the git repository. Move them there or create a symbolic link.

## 2. Start the database

```bash
docker compose -f docker-compose.yml up -d postgres
```

## 3. Start the backend
```bash
cd backend
sbt
```

Inside the sbt console run the command `run`

After compiling, the backend will be running on port `8182`. It will start scanning the `media` directory and log its progress.

## 4. Start the frontend
```bash
cd frontend
fnm use # or nvm use
pnpm install
pnpm run generate # generate API client from OpenAPI spec
pnpm run dev
```

The frontend will be running on port `5173`. It will proxy all API requests to the backend on port `8182`.

## 5. Open your web browser

Navigate to `http://localhost:5173`

Back to the [main README](../README.md).
