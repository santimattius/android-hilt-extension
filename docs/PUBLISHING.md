# Publishing to Maven Central

This template uses the [gradle-maven-publish-plugin](https://github.com/vanniktech/gradle-maven-publish-plugin) to publish the `:library` module to Maven Central.

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
```

For automatic release (no manual “Publish” in Central Portal):

```bash
./gradlew publishToMavenCentral
# With automaticRelease = true (already set in this template), the plugin will release automatically.
```

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

1. Update `libraryVersion` in `gradle.properties` to the new version (e.g. `1.0.0`).
2. Commit, push, then create a **GitHub Release** with tag `v1.0.0` (same as version, with `v` prefix).
3. The **Publish to Maven Central** workflow will run and publish with version `1.0.0`.

The workflow uses the tag to set `libraryVersion` for that run, so the value in `gradle.properties` is only a default; the tag is the source of truth for the published version.

## Customizing POM and coordinates

- **Coordinates**: `gradle.properties` → `libraryGroupId`, `libraryArtifactId`, `libraryVersion`.
- **POM** (name, description, URL, license, developers, SCM): optional `gradle.properties` entries are documented at the top of `gradle.properties`. You can also set them in `library/build.gradle.kts` in the `mavenPublishing.pom { }` block.

Replace placeholder URLs and developer IDs (e.g. `your-org`, `your-username`) with your own before publishing.
