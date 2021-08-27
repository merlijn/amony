# Amony

A simple web app that scans a local directory for `.mp4` files and allows you to browse & play them in a web browser.

See the screenshot below: 

![](docs/screenshot-2021-08-27.png)

A live demo is available at [https://amony.nl](https://amony.nl). It is running on single [GCE](https://cloud.google.com/compute/) `e2-micro` instance.

All example videos were sourced from the [Pexels](https://www.pexels.com).

## How to use

For now there are no pre-packaged binaries, so you will have to build it yourself. Start by cloning this repo. 

### Development mode

### Prerequisites
- npm
- scala & sbt
- ffmpeg

#### 1. Start the web client
```bash
cd web-client
npm install --save
npm start
```

The web-client is now running on port `3000`

#### 2. Start the server
```
cd server
sbt run
```

The server is now running on port `8080`

#### 3. Open browser

Set location to `localhost:3000`. The web client will proxy all api requests to port `8080`.


### 'Production' mode

### Prerequisites

- npm
- scala & sbt
- docker

### Start the app in 'production mode'

1. Build the web client

```bash
cd web-client
npm install --save
npm run build
```

2. Build the server

```
cd server
sbt assembly
```

3. Build the docker image

```
docker build -t my-amony-app:latest .
```

The first time this can take a bit longer since it downloads & installs all dependencies for `ffmpeg`.

4. Run the docker image

```bash
docker run -v /path/to/my/videos:/usr/local/amony/videos -p 8080:8080
```