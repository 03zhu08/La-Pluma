#!/usr/bin/env bash

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$ROOT_DIR"

SUPPORTED_PLATFORMS=(
  windows-x86_64
  linux-x86_64
  macosx-x86_64
  macosx-arm64
)

print_usage() {
  cat <<'EOF'
Usage:
  bash ./build-platform-jars.sh
  bash ./build-platform-jars.sh windows-x86_64 macosx-arm64

Supported platforms:
  windows-x86_64
  linux-x86_64
  macosx-x86_64
  macosx-arm64
EOF
}

contains_platform() {
  local value="$1"
  shift
  local item
  for item in "$@"; do
    if [ "$item" = "$value" ]; then
      return 0
    fi
  done
  return 1
}

is_valid_java8_home() {
  local home="${1:-}"
  [ -n "$home" ] && [ -x "$home/bin/java" ] && [ -f "$home/lib/tools.jar" ]
}

select_java8_home() {
  local candidates=()
  if [ -n "${JAVA_HOME:-}" ]; then
    candidates+=("$JAVA_HOME")
  fi
  candidates+=(
    "/Library/Java/JavaVirtualMachines/jdk-1.8.jdk/Contents/Home"
    "/Library/Internet Plug-Ins/JavaAppletPlugin.plugin/Contents/Home"
  )

  local candidate
  for candidate in "${candidates[@]}"; do
    if is_valid_java8_home "$candidate"; then
      printf '%s\n' "$candidate"
      return 0
    fi
  done
  return 1
}

PLATFORMS=("$@")
if [ "${#PLATFORMS[@]}" -gt 0 ] && { [ "${PLATFORMS[0]}" = "--help" ] || [ "${PLATFORMS[0]}" = "-h" ]; }; then
  print_usage
  exit 0
fi

if [ "${#PLATFORMS[@]}" -eq 0 ]; then
  PLATFORMS=("${SUPPORTED_PLATFORMS[@]}")
fi

for platform in "${PLATFORMS[@]}"; do
  if ! contains_platform "$platform" "${SUPPORTED_PLATFORMS[@]}"; then
    echo "Unsupported platform: ${platform}" >&2
    echo >&2
    print_usage >&2
    exit 1
  fi
done

if JAVA8_HOME="$(select_java8_home)"; then
  export JAVA_HOME="$JAVA8_HOME"
  export PATH="$JAVA_HOME/bin:$PATH"
  echo "==> Using JAVA_HOME=${JAVA_HOME}"
else
  echo "Could not locate a usable JDK 8 (tools.jar required)." >&2
  echo "Please install a full JDK 8 and re-run." >&2
  exit 1
fi

echo "==> Cleaning previous build outputs"
./gradlew clean

for platform in "${PLATFORMS[@]}"; do
  echo
  echo "==> Building platform: ${platform}"
  ./gradlew build -PtargetPlatform="${platform}"
done

echo
echo "==> Finished. Artifacts are in build/libs"
