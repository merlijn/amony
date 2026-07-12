# Amony media server

This is a media management server project. It can be used to view and organize media files.

## Components

### /backend

The backend written in Scala 3 using the Cats Effect ecosystem.

The purpose of the backend is to host media files from a directory and provide an API for the frontend to interact with. 
It uses a PostgreSQL database to store metadata about the media files. 
It uses Tapir to define the API endpoints and generate an OpenAPI specification.

Useful commands:
- `sbt generateSpec` - Generates the OpenAPI specification and places it in the frontend folder.

### /frontend

The frontend is a React application that interacts with the backend API to browse and view media files and their metadata.

Useful commands:
- `npm run generate` - Generate API client code from OpenAPI spec
- `npm run dev` - Start the development server
- `npm run build` - Build the production version of the frontend

### /docker

This directory contains various Dockerfiles and scripts and settings for the docker compose setup.