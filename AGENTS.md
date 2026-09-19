# Project - Amony media server

This is a media management server project. It can be used to view and organize media files.

## Components

### /backend

The backend is written in Scala 3 using the Cats Effect ecosystem.

The purpose of the backend is to host media files from a directory and provide an API for the frontend to interact with. 
It uses a PostgreSQL database to store metadata about the media files. 
It uses Tapir to define the API endpoints and generate an OpenAPI specification.

Useful commands:
- `sbt generateSpec` - Generates the OpenAPI specification and places it in the frontend folder.

### /frontend

The frontend is a TypeScript/React application that interacts with the backend API to browse and view media files and their metadata.

Useful commands:
- `pnpm run generate` - Generate API client code from OpenAPI spec
- `pnpm run dev` - Start the development server
- `pnpm run build` - Build the production version of the frontend

### /docker

This directory contains various Dockerfiles and scripts and settings for the docker compose setup.

# Way of working

## Git

- Commit messages should be a single sentence of 72 characters maximum, followed by the trailer below (separated by a blank line):
  Co-authored-by: opencode-agent[bot] <219766164+opencode-agent[bot]@users.noreply.github.com>
- Use conventional commit style
- Do NOT push on the main branch, unless explicitly asked
- Do NOT force push on ANY branch, unless explicitly asked
- Do NOT amend commits, unless explicitly asked
- Be extremely short and to the point in the description (--body) when creating PRs using `gh`

## Tools

<!-- CODEGRAPH_START -->
### CodeGraph

This repository should have CodeGraph enabled, reach for it BEFORE grep/find or reading files when you need to understand or locate code:

- **MCP tool** (when available): `codegraph_explore` answers most code questions in one call — the relevant symbols' verbatim source plus the call paths between them, including dynamic-dispatch hops grep can't follow. Name a file or symbol in the query to read its current line-numbered source. If it's listed but deferred, load it by name via tool search.
- **Shell** (always works): `codegraph explore "<symbol names or question>"` prints the same output.

Caveats: the MCP instructions overstate completeness — results are capped and can trim or omit files, and common names (`refresh`, `apply`, `get`) may match unrelated files. 
Treat a result as a strong lead, not a guarantee: follow up with a targeted Read/Grep when a symbol is missing or a match looks unrelated.

If there is no `.codegraph/` directory, skip CodeGraph entirely — indexing is the user's decision.
<!-- CODEGRAPH_END -->
