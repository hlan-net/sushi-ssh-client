#!/usr/bin/env bash
# check-versions.sh — compare every dependency in app/build.gradle.kts with the
# latest stable release on Maven Central / Google Maven, plus AGP and Gradle.
#
# Usage: ./scripts/check-versions.sh
#
# Read-only; needs only bash, curl, grep, sed, sort. Pre-releases (alpha, beta,
# rc, snapshot, dev, preview) are ignored when picking "latest". A version with a
# classifier suffix (e.g. guava's -android) is compared only against versions
# with the same suffix.
#
# This is the audit behind docs/process/DEPENDENCY_LIFECYCLE.md. Dependabot
# proposes the same bumps one at a time; this prints the whole picture at once.

set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
GRADLE_FILE="$ROOT/app/build.gradle.kts"
ROOT_GRADLE="$ROOT/build.gradle.kts"
WRAPPER="$ROOT/gradle/wrapper/gradle-wrapper.properties"

TIMEOUT=20
PRE='alpha|beta|rc|snapshot|dev|preview|eap|m[0-9]'

fetch() { curl -sS --max-time "$TIMEOUT" "$1" 2>/dev/null || true; }

# pick_latest <current> — reads candidate versions on stdin, prints the newest
# stable one that matches the current version's classifier suffix.
pick_latest() {
  local current="$1" suffix="" all
  all=$(cat)
  # Google API revisions look like v3-rev20260901-2.0.0: compare within the same API version.
  if [[ "$current" =~ ^v[0-9]+-rev ]]; then
    echo "$all" | (grep -E "^${current%%-rev*}-rev" || true) | sort -V | tail -1; return
  fi
  if [[ "$current" == *-* ]]; then suffix="-${current##*-}"; fi
  local list
  list=$(echo "$all" | (grep -E '^[0-9]' || true) | (grep -vEi "$PRE" || true))
  # Nothing stable published yet (e.g. a library still in beta): say so instead of "not found".
  if [[ -z "$list" && -n "$all" ]]; then echo "no stable (newest: $(echo "$all" | sort -V | tail -1))"; return; fi
  if [[ -n "$suffix" ]]; then
    echo "$list" | (grep -E -- "${suffix}\$" || true) | sort -V | tail -1
  else
    echo "$list" | (grep -vE -- '-[a-zA-Z]+$' || true) | sort -V | tail -1
  fi
}

maven_central() { # group artifact -> versions, one per line
  local path="https://repo1.maven.org/maven2/${1//.//}/$2/maven-metadata.xml"
  fetch "$path" | (grep -oE '<version>[^<]+</version>' || true) | sed 's/<[^>]*>//g'
}

google_maven() { # group artifact -> versions, one per line
  local idx="https://dl.google.com/dl/android/maven2/${1//.//}/group-index.xml"
  fetch "$idx" | (grep -oE "<$2 versions=\"[^\"]+\"" || true) | sed 's/.*versions="//;s/"//' | tr ',' '\n'
}

report() { # coordinate current latest
  local status="  ok"
  if [[ -z "$3" ]]; then status="  ?? (not found)"
  elif [[ "$2" != "$3" ]]; then status="  -> $3"
  fi
  printf '%-62s %-22s%s\n' "$1" "$2" "$status"
}

echo "Dependencies (app/build.gradle.kts)"
echo "-----------------------------------"
coroutines_version=$(grep -oE 'coroutines_version = "[^"]+"' "$GRADLE_FILE" | sed 's/.*"\(.*\)"/\1/')
grep -oE '(implementation|testImplementation|androidTestImplementation)\("[^"]+"\)' "$GRADLE_FILE" \
  | sed -E 's/.*\("//;s/"\)//' | sort -u | while IFS=: read -r group artifact version; do
  version="${version//\$coroutines_version/$coroutines_version}"
  versions=$(maven_central "$group" "$artifact")
  [[ -z "$versions" ]] && versions=$(google_maven "$group" "$artifact")
  latest=$(echo "$versions" | pick_latest "$version")
  report "$group:$artifact" "$version" "$latest"
done

echo
echo "Toolchain"
echo "---------"
agp=$(grep -oE 'com.android.application"\) version "[^"]+"' "$ROOT_GRADLE" | sed 's/.*"\([^"]*\)"$/\1/')
agp_latest=$(google_maven com.android.tools.build gradle | pick_latest "$agp")
report "Android Gradle Plugin" "$agp" "$agp_latest"
gradle=$(grep distributionUrl "$WRAPPER" | grep -oE 'gradle-[0-9.]+' | sed 's/gradle-//;s/\.$//')
gradle_latest=$(fetch https://services.gradle.org/versions/current | grep -oE '"version" *: *"[^"]+"' | sed 's/.*"\([^"]*\)"$/\1/')
report "Gradle wrapper" "$gradle" "$gradle_latest"
ndk=$(grep -oE 'ndkVersion = "[^"]+"' "$GRADLE_FILE" | sed 's/.*"\(.*\)"/\1/')
cmake=$(grep -A3 'cmake {' "$GRADLE_FILE" | grep -oE 'version = "[0-9.]+"' | head -1 | sed 's/.*"\(.*\)"/\1/')
repo=$(fetch https://dl.google.com/android/repository/repository2-3.xml)
ndk_latest=$(echo "$repo" | grep -oE 'path="ndk;[^"]+"' | sed 's/path="ndk;//;s/"//' | sort -V | tail -1)
cmake_latest=$(echo "$repo" | grep -oE 'path="cmake;[^"]+"' | sed 's/path="cmake;//;s/"//' | sort -V | tail -1)
report "NDK" "$ndk" "$ndk_latest"
report "CMake" "${cmake:-?}" "$cmake_latest"

echo
echo "Services with named resources (check by hand)"
echo "---------------------------------------------"
grep -oE 'MODEL_[A-Z]+ = "[^"]+"' "$ROOT/app/src/main/java/net/hlan/sushi/GeminiClient.kt" | sed 's/^/Gemini  /'
echo "        current list: https://ai.google.dev/gemini-api/docs/models"
echo "ML Kit  com.google.mlkit:genai-prompt — no stable line yet; API may change between betas"
echo "Drive   google-api-services-drive rev — see the row above; the REST API itself is v3"
