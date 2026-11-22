#!/usr/bin/env bash
#
# Updates the checkout, fetches the web client artifact that matches it and redeploys the
# compose stack. Arguments are passed to docker compose, so deployment-specific overlays and
# profiles belong on the command line.
#
# Environment:
#   DEPLOY_DIR          Checkout to deploy (defaults to the current directory)
#   DEPLOY_REF          Git ref to deploy (defaults to main)
#   WEB_IMAGE           Web client artifact image (defaults to ghcr.io/merlijn/amony-web)
#   PULL_SERVICES       Services to pull (defaults to "amony web")
#   RECREATE_SERVICES   Services to force-recreate after `up` (defaults to "web")
#
# Example:
#   ./deployment/deploy.sh -f docker-compose.yml
set -euo pipefail

deploy_dir="${DEPLOY_DIR:-$PWD}"
deploy_ref="${DEPLOY_REF:-main}"
web_image="${WEB_IMAGE:-ghcr.io/merlijn/amony-web}"
pull_services="${PULL_SERVICES:-amony web}"
recreate_services="${RECREATE_SERVICES:-web}"

cd "$deploy_dir"

git fetch --prune origin
git checkout "$deploy_ref"
if git symbolic-ref -q HEAD >/dev/null; then
  git pull --ff-only
fi

# The web client is published per frontend tree, so the tag is derived from this checkout and
# the deployed assets always match the deployed commit.
fe_tree="$(git rev-parse HEAD:frontend)"
web_image="$(printf '%s' "$web_image" | tr '[:upper:]' '[:lower:]')"
web_ref="$web_image:fe-$fe_tree"
echo "Deploying web client $web_ref"

pull_dir="$(mktemp -d)"
trap 'rm -rf "$pull_dir"' EXIT
docker run --rm --user "$(id -u):$(id -g)" -v "$pull_dir:/out" \
  ghcr.io/oras-project/oras:v1.3.0 pull -o /out "$web_ref"
mkdir -p "web/releases/$fe_tree"
tar -xzf "$pull_dir/frontend-dist.tar.gz" -C "web/releases/$fe_tree"
ln -sfn "releases/$fe_tree" web/current
while IFS= read -r old_release; do
  rm -rf "web/releases/$old_release"
done < <(ls -1t web/releases | tail -n +4)

pull_list=()
if [[ -n "$pull_services" ]]; then
  read -r -a pull_list <<< "$pull_services"
fi
if ((${#pull_list[@]})); then
  docker compose "$@" pull "${pull_list[@]}"
fi

# --build refreshes locally built images, such as the demo certbot image.
docker compose "$@" up -d --build

# Bind-mounted configuration is not part of the container spec, so those services need to be
# recreated to pick up config changes.
recreate_list=()
if [[ -n "$recreate_services" ]]; then
  read -r -a recreate_list <<< "$recreate_services"
fi
if ((${#recreate_list[@]})); then
  docker compose "$@" up -d --no-deps --force-recreate "${recreate_list[@]}"
fi

docker image prune -f
