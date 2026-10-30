# dopaminekt

Kotlin Multiplatform core for dopaminekt. Targets: JVM, Android, iosArm64, iosSimulatorArm64.

It follows dopaminejs 3.2.0.

- `Progress(storage, config)` is one player: XP, levels, streaks, play stats and achievements, saved as a version 3 document. Call `init()` first. `addXp`, `record`, `unlockAchievement`, `import`, `export`, `reset(overwrite)` and `snapshot` work on it.
- `Progress.on(name, listener)` gives events: `xp_gained`, `level_up`, `achievement_unlocked`, `personal_best`, `streak_updated`, `player_reset`, `save_failed` and `load_failed`.
- `Progress.use(middleware)` adds hooks that run before and after XP, records and unlocks. A before hook can reject a call or change its input.
- `validate(json)` cleans any JSON value into a version 3 document, migrating a 2.x save. Entries it cannot use are dropped. It throws `DopamineError` with the code `invalid_document`, `unsupported_version` or `too_many_devices`.
- `merge(a, b)` joins two saves. The result does not depend on argument order.
- `levelFor(xp)` and `thresholdFor(level)` give the level curve.
- `Clock` and `SystemClock(zone)` name the local day. `StoragePort` and `MemoryStorage` hold saved text.

## Plugins and parts

Core defines the contracts a plugin or a part needs and ships no implementation of them.

- Parts: `Haptics`, `SoundPlayer`, `ParticleRenderer`, `RewardUi` and `Parts`, with `PartName`, `BurstKind`, `PopupKind`, `PopupTier` and the value types `Point`, `BurstOptions`, `HudState`, `Popup` and `FloatingText`.
- Plugins: `DopaminePlugin`, `PluginContext`, `PluginEvents`, `Logger`, `Env` and `Disposer`. `HOST_API` is the plugin API version (3) and `isValidPluginName(name)` checks a plugin name.
- A plugin sends its own events with `Progress.emit(ProgressEvent.Custom("name:event", data))`. Names without a `:` or with a leading `:` throw `DopamineError("plugin_event")`, so a plugin cannot fake a core event.

An app or caller supplies the parts and runs the plugins. The public API is recorded in `core/api`. `./gradlew :core:updateKotlinAbi` records a change, and `check` fails on one that is not recorded.

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

The fixture runner (`FixtureRunnerTest`) runs every case in every file through `Progress`, in Asia/Manila and America/Los_Angeles, with the clock set to the case's local time in that zone. None are skipped, and the test pins how many cases run per file.

To move to a new dopaminejs version, change the pin, run `syncFixtures` and commit the result.

## Build and check

```
./gradlew check
```

CI runs this on Linux in the Asia/Manila and America/Los_Angeles time zones. Linux cannot build iOS klibs, so iOS targets are skipped there. Run `./gradlew iosSimulatorArm64Test` on a Mac.

## Release

Pushing a `v<version>` tag starts the Publish workflow. It runs on macOS in the `maven-central` environment, which holds the secrets and waits for approval before the job starts. Maven Central cannot take a release back, so check the artifacts before approving.

1. Set `VERSION_NAME` in `gradle.properties` to the release version, commit it to `main`, and tag that commit `v<version>`.
2. Run `./gradlew publishToMavenLocal` and read the artifact list and the POM.
3. Push the tag. The workflow refuses unless the tag matches `v<version>`, the version is not a SNAPSHOT and the commit is on `main`. Then it runs `./gradlew check iosSimulatorArm64Test` and `./gradlew publishAndReleaseToMavenCentral`.
4. Approve the run in the Actions tab.

For a tag pushed before the workflow existed, run Publish by hand from the Actions tab with the tag name.

The environment allows deployments only from `main` and tags matching `v*`, and needs four secrets: `MAVEN_CENTRAL_USERNAME`, `MAVEN_CENTRAL_PASSWORD` (a Central portal token), `SIGNING_KEY` (the armored private key) and `SIGNING_KEY_PASSWORD`.

To publish from a Mac instead, put `ORG_GRADLE_PROJECT_mavenCentralUsername`, `ORG_GRADLE_PROJECT_mavenCentralPassword`, `ORG_GRADLE_PROJECT_signingInMemoryKey` and `ORG_GRADLE_PROJECT_signingInMemoryKeyPassword` in the environment and run `scripts/publish.sh` from the tagged commit. It makes the same checks.

Without a signing key in the environment, builds and `check` still work. Publications are signed only when `signingInMemoryKey` is set.

## License

MPL-2.0. See `LICENSE`.
