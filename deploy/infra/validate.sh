#!/usr/bin/env bash
# Credential-free formatting/schema validation of every deploy/infra module with a checksum-pinned OpenTofu binary.
# bash deploy/infra/validate.sh. Linux amd64 only; needs curl, unzip, and sha256sum or shasum.
set -euo pipefail
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
version=1.10.6
sha256=15b7bed76420b50da3e121769c43341df8cd57d751ca14e6dbe9c850124c6dac

fail() { printf 'validate: %s\n' "$*" >&2; exit 1; }

[[ "$(uname -s)" == Linux && "$(uname -m)" == x86_64 ]] ||
  fail "use OpenTofu $version on this platform; the validation installer pins Linux amd64 only"

binary="$root/build/infra-tools/tofu"
if [[ ! -e "$binary" ]]; then
  archive="$(mktemp)"
  trap 'rm -f "$archive" "$binary.partial"' EXIT
  curl -fsSL --connect-timeout 60 --speed-time 60 --speed-limit 1 -o "$archive" \
    "https://github.com/opentofu/opentofu/releases/download/v$version/tofu_${version}_linux_amd64.zip" ||
    fail "cannot download OpenTofu $version"
  if command -v sha256sum >/dev/null; then actual="$(sha256sum < "$archive")"; else actual="$(shasum -a 256 < "$archive")"; fi
  [[ "${actual%% *}" == "$sha256" ]] || fail "OpenTofu checksum mismatch"
  mkdir -p "$(dirname "$binary")"
  unzip -p "$archive" tofu > "$binary.partial" && [[ -s "$binary.partial" ]] || fail "no tofu binary in the archive"
  chmod 755 "$binary.partial"
  mv -f "$binary.partial" "$binary"
fi

for versions in "$root"/deploy/infra/*/versions.tf; do
  folder="$(dirname "$versions")"
  "$binary" -chdir="$folder" fmt -check
  "$binary" -chdir="$folder" init -backend=false -input=false -lockfile=readonly
  "$binary" -chdir="$folder" validate -no-color
done
