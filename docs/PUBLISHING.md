# Publishing to Maven Central

This template uses the [gradle-maven-publish-plugin](https://github.com/vanniktech/gradle-maven-publish-plugin) to publish the library modules to Maven Central. Shared publishing configuration lives in the `hiltext.publishing` convention plugin of the `build-logic` included build.

## Prerequisites

1. **Sonatype Central account**  
   - Sign up at [central.sonatype.com](https://central.sonatype.com/) and claim your group ID (e.g. `com.yourcompany`).

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

## Published artifacts

Four artifacts are published, each under its own artifact id (the module name) with the group `com.santimattius.android`:

| Artifact id | Module | Build |
|-------------|--------|-------|
| `hilt-trace-runtime` | `:hilt-trace-runtime` | root build |
| `hilt-trace-compiler` | `:hilt-trace-compiler` | root build |
| `hilt-coroutines-runtime` | `:hilt-coroutines-runtime` | root build |
| `hilt-trace-gradle-plugin` | `hilt-trace-gradle-plugin/` | included build |

`build-logic` is an included build too, but it holds only the convention plugins and is not published. The modules are added slice by slice, so an artifact appears here once its module exists.

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

For automatic release (no manual “Publish” in Central Portal), nothing more is needed: `automaticRelease = true` is already set in the `hiltext.publishing` convention plugin, so the plugin releases automatically.

## CI/CD with GitHub Actions

Publishing runs when a **GitHub Release** is **published**. The release tag must match the library version (e.g. tag `v1.0.0` → version `1.0.0`).

### 1. Configure repository secrets

In **Settings → Secrets and variables → Actions**, add:

| Secret                     | Description |
|----------------------------|-------------|
| `MAVEN_CENTRAL_USERNAME`   | Central Portal token username |
| `MAVEN_CENTRAL_PASSWORD`   | Central Portal token password |
| `SIGNING_KEY`              | Full ASCII-armored GPG private key (multi-line) |
| `SIGNING_KEY_PASSWORD`     | (Optional) GPG key passphrase |
| `SIGNING_KEY_ID`           | (Optional) GPG key ID (e.g. 8-character hex) |

### 2. Release flow

1. Update the `libraryVersion` default to the new version (e.g. `1.0.0`) in `gradle.properties`, `build-logic/gradle.properties` and `hilt-trace-gradle-plugin/gradle.properties`; `./gradlew verifyVersionAlignment` fails if they differ.
2. Commit, push, then create a **GitHub Release** with tag `v1.0.0` (same as version, with `v` prefix).
3. The **Publish to Maven Central** workflow will run and publish with version `1.0.0`.

The workflow runs on JDK 25 (the Gradle daemon JVM pinned in `gradle/gradle-daemon-jvm.properties`) while the library bytecode targets JVM 17. It passes the tag version as `ORG_GRADLE_PROJECT_libraryVersion`, which reaches the root build and the included builds, so the defaults in the `gradle.properties` files are only fallbacks; the tag is the source of truth for the published version.

## Customizing POM and coordinates

- **Coordinates**: `gradle.properties` → `libraryGroupId` and `libraryVersion` (plus the included-build defaults described above). The artifact id is the module name.
- **POM** (name, description, URL, license, developers, SCM): configured in `build-logic/src/main/kotlin/com/santimattius/android/hilt/buildlogic/PublishingConventionPlugin.kt` and customizable via `gradle.properties`.
