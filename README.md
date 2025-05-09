# Amony

A simple web app that scans a local directory for `.mp4` files and lets you browse & play them in a web browser.

Example screenshot:

![](docs/app-screenshot.png)

A live demo is available at [https://amony.nl](https://amony.nl). It is running on single [GCE](https://cloud.google.com/compute/) `e2-micro` instance.

**Note:** All videos on the demo site are free and sourced from [Pexels](https://www.pexels.com/license/)

# How to use

## Pre-packaged docker image

### Prerequisites
- [docker](https://www.docker.com/get-started)

### 1. Pull the image

The image is hosted on a public Google Cloud docker repository.

```bash
docker pull europe-west4-docker.pkg.dev/amony-04c85b/docker-images/amony/app:latest
```

### 2. Run the docker image

The default location for the media files is `/media`. You can mount a local directory to it.
The default location for the solr index, database and config files is `/app/data`. It is recommended to mount a local directory to it to persist the data.

```bash
docker run -v /path/to/my/media:/media -v /path/to/my/amony-data:/app/data -p 8080:8080 --name amony europe-west4-docker.pkg.dev/amony-04c85b/docker-images/amony/amony-app:latest
```

### 3. Usage

The webapp now runs at http://localhost:8080 

**Notes:**
- It takes some time to index the videos. Check progress with `docker logs amony`
- Requires write access in the chosen directory, a `.amony` directory will be created with the index data, thumbnails and 3 seconds preview videos.

## Development mode

### Prerequisites
- [npm](https://docs.npmjs.com/downloading-and-installing-node-js-and-npm)
- [scala 2.13](https://scala-lang.org/) & [sbt](https://www.scala-sbt.org/)
- [fmpeg](https://ffmpeg.org/)

### 1. Prepare your media files

In dev mode the media files are expected to in a directory named `media` inside the git repository. Move them there or create a symbolic link.

### 2. Start the backend
```
cd backend
sbt
```

Inside the sbt console run the command `~reStart`

After compiling the backend will be running on port `8080`. It will start scanning the `videos` directory and log its progress.

### 3. Start the frontend
```bash
cd frontend
npm install --save
npm start
```

After compiling the frontend will be running on port `3000`. It will proxy all api requests to the backend on port `8080`.

### 4. Open your web browser

Set location to `localhost:3000`


## Build a docker image

### Prerequisites

- [npm](https://docs.npmjs.com/downloading-and-installing-node-js-and-npm)
- [scala 2.13](https://scala-lang.org/) & [sbt](https://www.scala-sbt.org/)
- [docker](https://www.docker.com/get-started)

### 1. Build the web client

```bash
cd frontend
npm install --save
npm run build
```

### 2. Build the docker image

```
cd server
sbt web-server/jibDockerBuild
```

### 4. Run the docker image

```bash
docker run -v /path/to/my/media:/media -v /path/to/my/amony-data:/app/data -p 8080:8080 --name amony europe-west4-docker.pkg.dev/amony-04c85b/docker-images/amony/amony-app:latest
```
