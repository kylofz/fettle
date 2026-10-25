#!/usr/bin/env bash
set -euo pipefail

tag="${1:?Usage: scripts/release.sh vMAJOR.MINOR.PATCH}"
version="$(sed -n 's/^releaseVersion=//p' gradle.properties)"

if [[ -z "$version" || "$tag" != "v$version" ]]; then
  echo "Tag '$tag' must match releaseVersion '$version' in gradle.properties." >&2
  exit 1
fi

./gradlew --no-daemon clean test :geyser:jar

scripts/verify-artifact.sh
artifact="geyser/build/libs/fettle-${version}.jar"

gh release create "$tag" "$artifact" --verify-tag --generate-notes --title "Fettle $version"
