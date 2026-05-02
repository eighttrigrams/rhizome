#!/usr/bin/env bash
set -euo pipefail

VERSION="${SQLITE_VEC_VERSION:-0.1.6}"
DEST="${1:-./.sqlite-vec}"

uname_s="$(uname -s)"
uname_m="$(uname -m)"

case "$uname_s/$uname_m" in
  Darwin/arm64)        slug="macos-aarch64";  ext="dylib" ;;
  Darwin/x86_64)       slug="macos-x86_64";   ext="dylib" ;;
  Linux/x86_64)        slug="linux-x86_64";   ext="so"    ;;
  Linux/aarch64)       slug="linux-aarch64";  ext="so"    ;;
  *) echo "unsupported platform: $uname_s/$uname_m" >&2; exit 1 ;;
esac

mkdir -p "$DEST"

if [ -f "$DEST/vec0.$ext" ]; then
  echo "sqlite-vec already installed at $DEST/vec0.$ext"
  exit 0
fi

url="https://github.com/asg017/sqlite-vec/releases/download/v${VERSION}/sqlite-vec-${VERSION}-loadable-${slug}.tar.gz"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

echo "downloading $url"
curl -fsSL "$url" -o "$tmp/sqlite-vec.tar.gz"
tar -xzf "$tmp/sqlite-vec.tar.gz" -C "$tmp"

found="$(find "$tmp" -name "vec0.$ext" -maxdepth 3 | head -n 1)"
if [ -z "$found" ]; then
  echo "could not find vec0.$ext in archive" >&2
  ls -R "$tmp" >&2
  exit 1
fi
cp "$found" "$DEST/vec0.$ext"
echo "installed $DEST/vec0.$ext"
