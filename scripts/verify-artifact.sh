#!/usr/bin/env bash
set -euo pipefail

version="$(sed -n 's/^releaseVersion=//p' gradle.properties)"
artifact="geyser/build/libs/fettle-${version}.jar"

if [[ -z "$version" || ! -f "$artifact" ]]; then
  echo "Expected Fettle artifact not found: $artifact" >&2
  exit 1
fi

manifest="$(unzip -p "$artifact" extension.yml)"
grep -Fxq "version: $version" <<< "$manifest"
grep -Fxq "main: me.kylofz.fettle.geyser.FettleExtension" <<< "$manifest"
jar --list --file "$artifact" | grep -Fx "me/kylofz/fettle/geyser/FettleExtension.class" >/dev/null
