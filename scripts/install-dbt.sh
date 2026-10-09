#!/usr/bin/env bash
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
version=2.0.5
case "$(uname -s)/$(uname -m)" in
  Darwin/arm64) platform=aarch64-apple-darwin; checksum=b598e91e8c7a237173973ae84607657f0b41060d4e71db54067ab92912e23bb1 ;;
  Darwin/x86_64) platform=x86_64-apple-darwin; checksum=e74e50198755db2388c2a42f9deb129f0b96f61072263cfb44e20b65bf451e83 ;;
  Linux/aarch64) platform=aarch64-unknown-linux-gnu; checksum=0afb18df3de93398516e1c3870487609be28a7619e0fb9139798b26537ce11c3 ;;
  Linux/x86_64) platform=x86_64-unknown-linux-gnu; checksum=da45bb51a200617b6390ae6804158b1de4a64070d1bd8caeb4f71a282bea899a ;;
  *) echo 'Supported platforms: macOS and Linux, ARM64 and x86_64.' >&2; exit 1 ;;
esac
archive="dbt-core-$version-$platform.tar.gz"
destination="$root/.tools/dbt"
if [[ -x "$destination/${archive%.tar.gz}/dbt" ]]; then
  "$destination/${archive%.tar.gz}/dbt" --version
  exit 0
fi
scratch="$(mktemp -d)"
trap 'rm -rf "$scratch"' EXIT
curl --fail --location --retry 3 "https://github.com/dbt-labs/dbt/releases/download/v$version/$archive" --output "$scratch/$archive"
if command -v sha256sum >/dev/null; then
  actual="$(sha256sum "$scratch/$archive" | cut -d ' ' -f 1)"
else
  actual="$(shasum -a 256 "$scratch/$archive" | cut -d ' ' -f 1)"
fi
[[ "$actual" == "$checksum" ]] || { echo 'dbt archive checksum mismatch.' >&2; exit 1; }
mkdir -p "$destination"
tar -xzf "$scratch/$archive" -C "$destination"
"$destination/${archive%.tar.gz}/dbt" --version
