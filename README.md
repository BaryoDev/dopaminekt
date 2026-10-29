# dopaminekt

Kotlin Multiplatform core for dopaminekt. Targets: JVM, Android, iosArm64, iosSimulatorArm64.

It follows dopaminejs 3.1.0. What is in so far is the saved player document and the math around it. The reward system itself is not in yet.

- `validate(json)` cleans any JSON value into a version 3 document, migrating a 2.x save. Entries it cannot use are dropped. It throws `DopamineError` with the code `invalid_document`, `unsupported_version` or `too_many_devices`.
- `merge(a, b)` joins two saves. The result does not depend on argument order.
- `levelFor(xp)` and `thresholdFor(level)` give the level curve.
- `Clock` and `SystemClock(zone)` name the local day. `StoragePort` and `MemoryStorage` hold saved text.

## Use it

```kotlin
// build.gradle.kts
repositories { mavenCentral() }

kotlin.sourceSets.commonMain.dependencies {
    implementation("dev.baryo.dopaminekt:core:0.0.1")
}
```

Nothing is published to Maven Central yet.

The module depends on kotlinx-coroutines-core, kotlinx-serialization-json and kotlinx-datetime, and on nothing else.

## Fixtures

The shared test fixtures come from the public [dopaminejs](https://www.npmjs.com/package/dopaminejs) npm package, pinned in `gradle/libs.versions.toml` (`dopaminejs`). They are committed in `spec/core/`, with a SHA-256 per file in `spec/CHECKSUMS`.

- `./gradlew syncFixtures` runs `npm pack dopaminejs@<pin>` against the public npm registry (your npm config is ignored, no install scripts run), checks the tarball version equals the pin, copies the package's `spec/` into `spec/core/` and rewrites `spec/CHECKSUMS`. It needs network and npm.
- `./gradlew verifyFixtures` runs offline and fails, naming the file, when `spec/core/` differs from `spec/CHECKSUMS`. `check` runs it.

`./gradlew generateFixtureSources` writes the fixtures and `spec/core/schema.json` into a Kotlin file under `core/build/`, so the JVM, Android and iOS tests read the same data with no file access. The test compile tasks run it. The generated file is not committed.

The fixture runner (`FixtureRunnerTest`) runs each case in Asia/Manila and America/Los_Angeles, with the clock set to the case's local time in that zone. It runs the validate and merge steps and the saved players that only need validate. Cases that need the reward system are skipped, and the run prints how many ran and were skipped per file.

To move to a new dopaminejs version, change the pin, run `syncFixtures` and commit the result.

## Build and check

```
./gradlew check
```

CI runs this on Linux in the Asia/Manila and America/Los_Angeles time zones. Linux cannot build iOS klibs, so iOS targets are skipped there. Run `./gradlew iosSimulatorArm64Test` on a Mac.

## Release

Releases are published from a Mac, never from CI. Maven Central cannot take a release back, so check the artifacts first.

1. Set `VERSION_NAME` in `gradle.properties` to the release version, commit it to `main`, and tag that commit `v<version>`.
2. Run `./gradlew publishToMavenLocal` and read the artifact list and the POM.
3. Put these in the environment: `ORG_GRADLE_PROJECT_mavenCentralUsername`, `ORG_GRADLE_PROJECT_mavenCentralPassword`, `ORG_GRADLE_PROJECT_signingInMemoryKey`, `ORG_GRADLE_PROJECT_signingInMemoryKeyPassword`.
4. Run `scripts/publish.sh`. It refuses unless HEAD carries the tag `v<version>`, the commit is on `main` and all four variables are set. Then it runs `./gradlew check iosSimulatorArm64Test` and `./gradlew publishAndReleaseToMavenCentral`.

Without a signing key in the environment, builds and `check` still work. Publications are signed only when `signingInMemoryKey` is set.

## License

MPL-2.0. See `LICENSE`.
