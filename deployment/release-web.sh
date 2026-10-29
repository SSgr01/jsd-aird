#!/usr/bin/env bash
# Usage: release-web.sh /absolute/unpacked/dist /srv/jsd-aird/web
# Run by the existing deployment account, after applying the static locations.
set -euo pipefail
dist=$(realpath "${1:?unpacked dist required}")
base=$(realpath -m "${2:?absolute deployment root required}")
[[ "$base" = /* && "$base" != / && "$base" != /srv && "$base" != /var ]] || exit 2
revision=$(python3 - "$dist" <<'PY'
import json, pathlib, re, sys
p = pathlib.Path(sys.argv[1])
info = json.loads((p / 'build-info.json').read_text())
assert info['component'] == 'frontend' and re.fullmatch('[a-f0-9]{40}', info['revision'])
assert (p / 'index.html').is_file()
manifest = json.loads((p / '.vite/manifest.json').read_text())
for chunk in manifest.values():
    for name in [chunk['file'], *chunk.get('css', []), *chunk.get('assets', [])]:
        target = (p / name).resolve()
        assert target.is_relative_to(p) and target.is_file(), name
print(info['revision'])
PY
)
release="$base/releases/$revision"
[[ ! -e "$release" ]] || { echo 'Release already exists; use rollback to activate it.' >&2; exit 2; }
mkdir -p "$base/releases" "$base/shared/assets"
staging=$(mktemp -d "$base/releases/.staging.XXXXXXXX")
trap 'rm -rf -- "$staging"' EXIT
cp -a "$dist/." "$staging/"
# Publish all content-hashed assets first. Never remove the old set on release.
cp -a "$staging/assets/." "$base/shared/assets/"
mv -- "$staging" "$release"
if [[ -L "$base/current" ]]; then readlink -f "$base/current" > "$base/previous-release"; fi
ln -s "$release" "$base/.current-$revision"
mv -Tf -- "$base/.current-$revision" "$base/current"
echo "Activated $revision. Verify headers and critical flows before the next batch."
echo 'Retain releases/assets for >= 7 days and >= 2 rollback releases; see README.'
