#!/usr/bin/env bash
# check-versions.sh — compare every dependency in app/build.gradle.kts with the
# latest stable release on Maven Central / Google Maven, plus AGP and Gradle.
#
# Usage: ./scripts/check-versions.sh            # the audit
#        ./scripts/check-versions.sh --self-test  # fixtures for the two selectors, no network
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
  # A suffix is a classifier (guava's -android, -jre) only when it is not a
  # pre-release tag: 1.0.0-beta4 must be compared against 1.0.0, not against -beta4.
  if [[ "$current" == *-* ]]; then
    local cand="-${current##*-}"
    if ! echo "$cand" | grep -qEi "$PRE"; then suffix="$cand"; fi
  fi
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

# sdk_latest <ndk|cmake> — reads the SDK repository XML on stdin and prints the
# highest package of that kind on the *stable* channel (channel-0) that carries no
# <preview> marker. The repository lists betas and release candidates too, with a
# higher number than the current stable; a plain "highest path" would pick them.
sdk_latest() {
  awk -v pfx="path=\"$1;" '
    BEGIN { RS = "<remotePackage " }
    index($0, pfx) == 1 {
      if ($0 !~ /channelRef ref="channel-0"/) next
      if ($0 ~ /<preview>/) next
      match($0, /path="[^"]+"/)
      v = substr($0, RSTART + length(pfx), RLENGTH - length(pfx) - 1)
      print v
    }' | sort -V | tail -1
}

# --self-test: run the two selectors against inline fixtures and exit.
if [[ "${1:-}" == "--self-test" ]]; then
  fail=0
  check() { if [[ "$2" == "$3" ]]; then echo "ok    $1 -> $2"; else echo "FAIL  $1 -> got '$2', want '$3'"; fail=1; fi; }
  check "beta current, stable published"   "$(printf '1.0.0-beta4\n1.0.0\n' | pick_latest 1.0.0-beta4)" "1.0.0"
  check "classifier kept (-android)"       "$(printf '33.7.1-android\n33.7.1-jre\n33.8.0-android\n' | pick_latest 33.7.1-android)" "33.8.0-android"
  check "beta only"                        "$(printf '1.0.0-beta3\n1.0.0-beta4\n' | pick_latest 1.0.0-beta4)" "no stable (newest: 1.0.0-beta4)"
  check "google api revision"              "$(printf 'v3-rev20230815-2.0.0\nv3-rev20260901-2.0.0\nv2-rev20270101-2.0.0\n' | pick_latest v3-rev20230815-2.0.0)" "v3-rev20260901-2.0.0"
  fixture='<sdk-repository>
<remotePackage path="ndk;30.0.1"><type-details/><revision><major>30</major></revision><channelRef ref="channel-0"/></remotePackage>
<remotePackage path="ndk;31.0.9"><type-details/><revision><major>31</major><preview>1</preview></revision><channelRef ref="channel-0"/></remotePackage>
<remotePackage path="ndk;32.0.1"><type-details/><revision><major>32</major></revision><channelRef ref="channel-1"/></remotePackage>
<remotePackage path="cmake;4.1.2"><channelRef ref="channel-0"/></remotePackage>
<remotePackage path="cmake;5.0.0"><channelRef ref="channel-3"/></remotePackage>
</sdk-repository>'
  check "ndk: preview and beta channel skipped" "$(echo "$fixture" | sdk_latest ndk)" "30.0.1"
  check "cmake: canary skipped"                 "$(echo "$fixture" | sdk_latest cmake)" "4.1.2"
  exit $fail
fi

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
ndk_latest=$(echo "$repo" | sdk_latest ndk)
cmake_latest=$(echo "$repo" | sdk_latest cmake)
report "NDK" "$ndk" "$ndk_latest"
report "CMake" "${cmake:-?}" "$cmake_latest"

echo
echo "Services with named resources (check by hand)"
echo "---------------------------------------------"
grep -oE 'MODEL_[A-Z]+ = "[^"]+"' "$ROOT/app/src/main/java/net/hlan/sushi/GeminiClient.kt" | sed 's/^/Gemini  /'
echo "        current list: https://ai.google.dev/gemini-api/docs/models"
echo "ML Kit  com.google.mlkit:genai-prompt — no stable line yet; API may change between betas"
echo "Drive   google-api-services-drive rev — see the row above; the REST API itself is v3"
