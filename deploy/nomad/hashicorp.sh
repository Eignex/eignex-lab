# shellcheck shell=bash
# Sourced by nomad/native-services.sh and infra/common/bake.sh: download HashiCorp binaries pinned in
# deploy/nomad/versions.json and verify them before use. Needs curl, unzip, and sha256sum or shasum. Written for
# macOS's bash 3.2. (nomad/sandbox.py and nomad/autoscaler.py still fetch their own in Python.)

hashicorp_versions="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/versions.json"

# hashicorp_pin <dotted path>: a string from versions.json, such as nomad.version or nomad.sha256.darwin_arm64.
hashicorp_pin() {
  awk -v want="$1" '
    { text = text $0 "\n" }
    END {
      n = length(text); depth = 0; expect_key = 0
      for (i = 1; i <= n; i++) {
        c = substr(text, i, 1)
        if (c == "{") { depth++; object[depth] = 1; expect_key = 1 }
        else if (c == "[") { depth++; object[depth] = 0; expect_key = 0 }
        else if (c == "}" || c == "]") depth--
        else if (c == ",") expect_key = object[depth]
        else if (c == ":") expect_key = 0
        else if (c == "\"") {
          s = ""
          for (i++; i <= n; i++) {
            c = substr(text, i, 1)
            if (c == "\\") { i++; s = s substr(text, i, 1) } else if (c == "\"") break; else s = s c
          }
          if (expect_key) { name[depth] = s; continue }
          path = name[1]
          for (d = 2; d <= depth; d++) path = path "." name[d]
          if (path == want) { print s; exit 0 }
        }
      }
      exit 1
    }' "$hashicorp_versions" || { echo "no $1 in $hashicorp_versions" >&2; return 1; }
}

# hashicorp_platform: this machine as HashiCorp names its builds, such as darwin_arm64.
hashicorp_platform() {
  local system arch
  case "$(uname -s)" in
  Darwin) system=darwin ;;
  Linux) system=linux ;;
  *) echo "no HashiCorp build for $(uname -s)" >&2; return 1 ;;
  esac
  case "$(uname -m)" in
  arm64 | aarch64) arch=arm64 ;;
  x86_64) arch=amd64 ;;
  *) echo "no HashiCorp build for $(uname -m)" >&2; return 1 ;;
  esac
  echo "${system}_$arch"
}

hashicorp_sha256() {
  if command -v sha256sum >/dev/null; then sha256sum < "$1"; else shasum -a 256 < "$1"; fi | awk '{ print $1 }'
}

# hashicorp_fetch <product> <platform> <output file>: download the pinned build of <product> (nomad or autoscaler, as
# versions.json keys them) for <platform>, check the archive against its pinned SHA-256 and write the binary in it to
# <output file>. Nothing is written when the check fails.
hashicorp_fetch() {
  local product="$1" platform="$2" output="$3" release version expected archive status=0
  case "$product" in
  nomad) release=nomad ;;
  autoscaler) release=nomad-autoscaler ;;
  *) echo "unknown HashiCorp product $product" >&2; return 1 ;;
  esac
  version="$(hashicorp_pin "$product.version")" && expected="$(hashicorp_pin "$product.sha256.$platform")" || return 1
  archive="$(mktemp)" || return 1
  # Idle rather than total limits: the archives are tens of megabytes.
  if ! curl -fsSL --connect-timeout 60 --speed-time 60 --speed-limit 1 -o "$archive" \
    "https://releases.hashicorp.com/$release/$version/${release}_${version}_$platform.zip"; then
    echo "cannot download $release $version for $platform" >&2
    status=1
  elif [[ "$(hashicorp_sha256 "$archive")" != "$expected" ]]; then
    echo "$release $version checksum mismatch for $platform" >&2
    status=1
  elif ! unzip -p "$archive" "$release" > "$output.partial" || [[ ! -s "$output.partial" ]]; then
    echo "no $release binary in the $release $version archive" >&2
    status=1
  else
    mv -f "$output.partial" "$output" || status=1
  fi
  rm -f "$archive" "$output.partial"
  return "$status"
}
