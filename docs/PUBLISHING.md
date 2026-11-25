# Publishing to Maven Central and JitPack

Maintainer guide for distributing the same four artifacts: **alpha versions go to JitPack; stable releases go to Maven Central**. Publishing uses the [gradle-maven-publish-plugin](https://github.com/vanniktech/gradle-maven-publish-plugin); the shared configuration is the `hiltext.publishing` convention plugin in the `build-logic` included build.

> [!NOTE]
> `1.0.0-alpha01` is available from Maven Central. That existing publication remains available; new alpha versions use JitPack. The steps below describe how maintainers publish stable releases (see [Setup](SETUP.md#consuming-the-artifacts)).

**Quick path:**

1. Complete the [prerequisites](#prerequisites) and add the [repository secrets](#1-configure-repository-secrets).
2. Set the new version in the three `gradle.properties` files and run `./gradlew verifyVersionAlignment`.
3. For a stable version, create a non-prerelease GitHub Release whose tag is `v<version>`; the workflow publishes. See [Release flow](#2-release-flow). For alpha versions, use the [JitPack path](#jitpack-the-same-four-artifacts).

## Prerequisites

1. **Sonatype Central account**
   - Sign up at [central.sonatype.com](https://central.sonatype.com/) and claim a namespace. This repository uses the namespace `io.github.santimattius`, which is the one claimed and verified in Central; every published coordinate, including the Gradle plugin marker, must sit under it.

2. **Central Portal token**
   - In Central Portal: **Profile → Generate User Token**.
   - Use this token as username/password for publishing (not your login password).

3. **GPG key for signing**
   - Create a key: [Sonatype – GPG](https://central.sonatype.org/publish/requirements/gpg/).
   - Distribute the public key (e.g. to keyserver.ubuntu.com).
   - Export the **ASCII-armored private key** for CI:
     ```bash
     gpg --export-secret-keys --armor <your-key-id>
     ```
   - Store the full output (including `-----BEGIN PGP PRIVATE KEY BLOCK-----` and `-----END PGP PRIVATE KEY BLOCK-----`) as a secret.

## Artifacts

Each artifact uses its module name as artifact id, with the group `io.github.santimattius.android`:

| Artifact id | Module | Build |
|-------------|--------|-------|
| `hilt-trace-runtime` | `:hilt-trace-runtime` | root build |
| `hilt-trace-compiler` | `:hilt-trace-compiler` | root build |
| `hilt-coroutines-runtime` | `:hilt-coroutines-runtime` | root build |
| `hilt-trace-gradle-plugin` | `hilt-trace-gradle-plugin/` | included build |

`build-logic` is an included build too, but it holds only the convention plugins and is not published.

## Included builds and the version

An included build does not read the root `gradle.properties`, so `build-logic/gradle.properties` and `hilt-trace-gradle-plugin/gradle.properties` each keep their own `libraryVersion` default. Override rules:

- `-PlibraryVersion=X` on the command line and `ORG_GRADLE_PROJECT_libraryVersion=X` in the environment reach every build, included builds too, and win over every default.
- Without an override, each build uses its own default. The root `verifyVersionAlignment` task (part of `check`) fails when those defaults differ, so change all of them together when bumping the version.

```bash
./gradlew verifyVersionAlignment
```

## Local publishing (optional)

Put credentials in `~/.gradle/gradle.properties` (do not commit):

```properties
mavenCentralUsername=your-token-username
mavenCentralPassword=your-token-password
signing.keyId=YOUR_KEY_ID
signing.password=your-gpg-passphrase
signing.secretKeyRingFile=/path/to/your/secring.gpg
```

Then run:

```bash
./gradlew publishToMavenCentral
# The included-build plugin is published with its own invocation:
./gradlew -p hilt-trace-gradle-plugin publishToMavenCentral
```

Releases are automatic (no manual "Publish" step in Central Portal): `hiltext.publishing` sets `automaticRelease = true`. Publications are signed unless `-Philtext.skipSigning=true` is passed; the `compat` CI job and JitPack use that opt-out for local Maven publication. Maven Central releases remain signed.

## CI/CD with GitHub Actions

The `Publish to Maven Central` workflow (`.github/workflows/publish-maven-central.yml`) receives **published GitHub Release** events, but its publication job runs only when the release is not marked as a prerelease and its tag contains no `-` qualifier. Alpha, beta and RC tags are skipped, even if incorrectly marked as stable. Use a stable version with a `v` prefix (tag `v1.0.0` publishes version `1.0.0`). Direct local publishing commands do not apply this CI filter; use them only for stable releases.

### 1. Configure repository secrets

In **Settings → Secrets and variables → Actions**, add:

| Secret | Required | Description |
|--------|----------|-------------|
| `MAVEN_CENTRAL_USERNAME` | Yes | Central Portal token username |
| `MAVEN_CENTRAL_PASSWORD` | Yes | Central Portal token password |
| `GPG_KEY_CONTENTS` | Yes | Full ASCII-armored GPG private key (multi-line) |
| `SIGNING_PASSWORD` | Optional | GPG key passphrase |
| `SIGNING_KEY_ID` | Optional | GPG key ID (for example, 8-character hex); if unset, the key is read from `GPG_KEY_CONTENTS` |

### 2. Release flow

1. Set the `libraryVersion` default to the new version (for example `1.0.0`) in `gradle.properties`, `build-logic/gradle.properties` and `hilt-trace-gradle-plugin/gradle.properties`. `./gradlew verifyVersionAlignment` fails if they differ.
2. Commit and push, then create a **GitHub Release** with tag `v1.0.0`, without selecting **Set as a pre-release**.
3. The workflow publishes the three root-build modules, then the Gradle plugin with its own invocation, all at version `1.0.0`.

The workflow runs on JDK 25 (the Gradle daemon JVM pinned in `gradle/gradle-daemon-jvm.properties`) while the library bytecode targets JVM 17. It passes the tag version as `ORG_GRADLE_PROJECT_libraryVersion`, which reaches the root build and the included builds, so the defaults in the `gradle.properties` files are only fallbacks; the tag is the source of truth for the published version.

## JitPack: the same four artifacts

JitPack distributes **alpha version tags only**; stable releases use Maven Central.
[`jitpack.yml`](../jitpack.yml) selects JDK 25 and invokes
[`scripts/publish-jitpack.sh`](../scripts/publish-jitpack.sh). The script publishes
exactly the three root libraries and the included-build Gradle plugin to Maven
local, including its plugin marker. It does not publish the sample, benchmarks,
or convention plugins, and does not upload anything to Maven Central.

JitPack supplies `VERSION`. The script accepts alpha tags such as
`v1.2.3-alpha01`, `1.2.3-alpha.1` or `v1.2.3-alpha`, preserving them verbatim.
Stable versions, beta/RC tags, branches and commit hashes are rejected before
Gradle runs. It uses the multi-module group
`com.github.santimattius.android-hilt-extension`. These coordinates are also
embedded into the plugin JAR so its injected runtime and compiler dependencies
use JitPack rather than Maven Central. No signing keys or Central credentials
are needed for this path.

### Consume libraries and the Gradle plugin

Replace `<alpha-tag>` with an alpha tag containing the JitPack configuration.
In the consuming project's `settings.gradle.kts`, add JitPack to both repository
sets. Resolve the plugin implementation explicitly to avoid depending on how
JitPack exposes the Gradle plugin marker:

```kotlin
pluginManagement {
    repositories {
        maven("https://jitpack.io")
        google()
        mavenCentral()
        gradlePluginPortal()
    }
    resolutionStrategy {
        eachPlugin {
            if (requested.id.id == "io.github.santimattius.android.hilt.trace") {
                useModule(
                    "com.github.santimattius.android-hilt-extension:" +
                        "hilt-trace-gradle-plugin:${requested.version}"
                )
            }
        }
    }
}

dependencyResolutionManagement {
    repositories {
        maven("https://jitpack.io")
        google()
        mavenCentral()
    }
}
```

In the module's `build.gradle.kts`, keep the existing Hilt/KSP configuration
from [Setup](SETUP.md) and use the same revision for all four artifacts:

```kotlin
plugins {
    id("io.github.santimattius.android.hilt.trace") version "<alpha-tag>"
}

dependencies {
    implementation(
        "com.github.santimattius.android-hilt-extension:" +
            "hilt-coroutines-runtime:<alpha-tag>"
    )
}
```

The plugin adds `hilt-trace-runtime` and `hilt-trace-compiler` automatically.
Avoid mixing Central and JitPack copies of the same artifacts in one consumer.

### Verify before requesting a hosted build

The entry-point contract tests run without Gradle or network access:

```bash
python3 -m unittest discover -s scripts -p test_publish_jitpack.py
```

For an isolated local publication using the same script:

```bash
repo="$(mktemp -d)"
VERSION=v1.2.3-alpha01 bash scripts/publish-jitpack.sh "-Dmaven.repo.local=$repo"
```

Local publication does not prove JitPack's hosted environment or POM rewriting.
After pushing the configuration and an alpha tag, request that
tag at [JitPack](https://jitpack.io/#santimattius/android-hilt-extension).
Check the build log, hosted POMs, and a consumer using both the plugin and a
library before announcing that revision as available. Its Android SDK must
support the platforms required by the build (the libraries use API 35 and the
sample uses API 37); the JDK alone does not establish SDK availability.

## Customizing POM and coordinates

- **Coordinates**: `libraryGroupId` and `libraryVersion` in `gradle.properties` (plus the included-build defaults described above). The artifact id is the module name.
- **POM** (name, description, URL, license, developers, SCM): configured in `build-logic/src/main/kotlin/io/github/santimattius/android/hilt/buildlogic/PublishingConventionPlugin.kt`. Override it with `POM_*` properties (`POM_DESCRIPTION`, `POM_INCEPTION_YEAR`, `POM_URL`, `POM_DEVELOPER_ID`, `POM_DEVELOPER_NAME`, `POM_DEVELOPER_URL`, `POM_SCM_URL`) in `gradle.properties`.
