#!/usr/bin/env bash
# Publishes core to Maven Central from the Mac. Run it from a clean checkout of a tagged commit on main.
set -euo pipefail

cd "$(dirname "$0")/.."

fail() { echo "publish.sh: $*" >&2; exit 1; }

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

clean_tree() {
  local dirty
  dirty=$(git status --porcelain) || fail "git status failed"
  [ -z "$dirty" ] || fail "working tree is not clean:"$'\n'"$dirty"
}
clean_tree

# Exactly one VERSION_NAME line, so the tag check and Gradle read the same value.
count=$(grep -c '^[[:space:]]*VERSION_NAME[[:space:]]*=' gradle.properties || true)
[ "$count" = "1" ] || fail "gradle.properties must have exactly one VERSION_NAME line, found $count"
version=$(grep '^[[:space:]]*VERSION_NAME[[:space:]]*=' gradle.properties | head -n 1 \
  | sed -e 's/^[^=]*=//' -e 's/\r//g' -e 's/^[[:space:]]*//' -e 's/[[:space:]]*$//')
[ -n "$version" ] || fail "VERSION_NAME is empty in gradle.properties"
case "$version" in
  *SNAPSHOT*) fail "VERSION_NAME $version is a SNAPSHOT. Releases only." ;;
esac
printf '%s\n' "$version" \
  | grep -Eq '^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)(-[A-Za-z0-9.]+)?$' \
  || fail "VERSION_NAME '$version' is not MAJOR.MINOR.PATCH (no leading zeros) with an optional -suffix"

tag=$(git tag --points-at HEAD | grep -Fx "v$version" || true)
[ -n "$tag" ] || fail "HEAD has no tag v$version. Tag the release commit first."

git fetch --quiet origin main || fail "cannot fetch origin/main to check the tagged commit"
git merge-base --is-ancestor HEAD origin/main || fail "HEAD is not on main"

# Never run Gradle here with --info or --debug. They can print properties, including the signing key.
./gradlew check iosSimulatorArm64Test

clean_tree
./gradlew publishAndReleaseToMavenCentral
