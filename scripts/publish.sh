#!/usr/bin/env bash
# Publishes core to Maven Central from the Mac. Run it from a clean checkout of a tagged commit on main.
set -euo pipefail

cd "$(dirname "$0")/.."

fail() { echo "publish.sh: $*" >&2; exit 1; }

version=$(sed -n 's/^VERSION_NAME=//p' gradle.properties)
[ -n "$version" ] || fail "VERSION_NAME not found in gradle.properties"

tag=$(git tag --points-at HEAD | grep -x "v$version" || true)
[ -n "$tag" ] || fail "HEAD has no tag v$version. Tag the release commit first."

git fetch --quiet origin main || fail "cannot fetch origin/main to check the tagged commit"
git merge-base --is-ancestor HEAD origin/main || fail "HEAD is not on main"

missing=()
for name in ORG_GRADLE_PROJECT_mavenCentralUsername \
            ORG_GRADLE_PROJECT_mavenCentralPassword \
            ORG_GRADLE_PROJECT_signingInMemoryKey \
            ORG_GRADLE_PROJECT_signingInMemoryKeyPassword; do
  [ -n "${!name:-}" ] || missing+=("$name")
done
if [ ${#missing[@]} -gt 0 ]; then
  fail "unset: ${missing[*]}. Nothing was published."
fi

./gradlew check iosSimulatorArm64Test
./gradlew publishAndReleaseToMavenCentral
