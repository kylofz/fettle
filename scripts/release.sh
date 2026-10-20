#!/usr/bin/env bash
set -euo pipefail

tag="${1:?Usage: scripts/release.sh vMAJOR.MINOR.PATCH}"
version="$(sed -n 's/^releaseVersion=//p' gradle.properties)"

if [[ -z "$version" || "$tag" != "v$version" ]]; then
  echo "Tag '$tag' must match releaseVersion '$version' in gradle.properties." >&2
  exit 1
fi

./gradlew --no-daemon clean test :geyser:jar

artifact="geyser/build/libs/fettle-${version}.jar"
if [[ ! -f "$artifact" ]]; then
  echo "Expected release artifact not found: $artifact" >&2
  exit 1
fi

manifest="$(unzip -p "$artifact" extension.yml)"
grep -Fxq "version: $version" <<< "$manifest"
grep -Fxq "main: me.kylofz.fettle.geyser.FettleExtension" <<< "$manifest"
jar --list --file "$artifact" | grep -Fx "me/kylofz/fettle/geyser/FettleExtension.class" >/dev/null

gh release create "$tag" "$artifact" --verify-tag --generate-notes --title "Fettle $version"
