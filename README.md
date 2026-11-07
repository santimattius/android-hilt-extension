# Android Hilt Extension

[![Maven Central](https://img.shields.io/maven-central/v/com.santimattius.android/hilt-extension?color=blue&label=Maven%20Central)](https://central.sonatype.com/artifact/com.santimattius.android/hilt-extension)
[![Android API](https://img.shields.io/badge/API-24%2B-brightgreen.svg?style=flat)](https://developer.android.com/guide/topics/manifest/uses-sdk-element#ApiLevels)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](https://opensource.org/licenses/Apache-2.0)
[![CI](https://github.com/santimattius/android-hilt-extension/actions/workflows/ci.yml/badge.svg)](https://github.com/santimattius/android-hilt-extension/actions/workflows/ci.yml)

A modern Android library providing extensions and utilities for Hilt dependency injection, with Maven Central publishing and GitHub Actions CI/CD.

---

## Description

This repository provides shared build logic and a sample application. The library modules are added in later changes:

- **`build-logic/`** — Included build with the convention plugins `hiltext.android-library`, `hiltext.jvm-library` and `hiltext.publishing` used by every published module. The earlier `com.santimattius.android:hilt-extension:1.0.0-alpha01` artifact stays on Maven Central.
- **`:app`** — Sample application for local development, integration testing, and demos.

Artifacts are published to [Maven Central](https://central.sonatype.com/) via the [gradle-maven-publish-plugin](https://github.com/vanniktech/gradle-maven-publish-plugin), with sources and Javadoc included and GPG signing configured for CI.

---

## Features

- **Kotlin** with latest stable tooling
- **Android Library** (AAR) with `minSdk 24`, `compileSdk 37`
- **Version catalog** (`gradle/libs.versions.toml`) for dependency management
- **Maven Central** publishing with automatic release and signing
- **GitHub Actions** — CI (build + test) and CD (publish on release)
- **Documentation** — [Publishing guide](docs/PUBLISHING.md) for Maven Central and secrets

---

## Requirements

- **JDK 17**
- **Android Studio** Ladybug (2024.2.1) or newer (or compatible IDE)
- **Android SDK** with `compileSdk 37`

---

## Installation

Add the dependency to your module’s `build.gradle.kts` (or `build.gradle`).

### Kotlin DSL

```kotlin
dependencies {
    implementation("com.santimattius.android:hilt-extension:1.0.0-alpha01")
}
```

### Groovy

```groovy
dependencies {
    implementation 'com.santimattius.android:hilt-extension:1.0.0-alpha01'
}
```

Ensure your project has **Maven Central** in its repositories (it is included by default in new Android projects):

```kotlin
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}
```

---

## Usage

Replace this section with a short description and code samples for your Hilt extensions. For example:

```kotlin
// Example usage of your Hilt extension API
```

---

## Project structure

```
android-hilt-extension/
├── app/                    # Sample app (not published)
├── build-logic/            # Included build: convention plugins (not published)
├── gradle/
│   └── libs.versions.toml  # Version catalog
├── .github/
│   └── workflows/
│       ├── ci.yml                    # Build & test on push/PR
│       └── publish-maven-central.yml # Publish on release
├── docs/
│   └── PUBLISHING.md       # Maven Central & CI/CD setup
├── build.gradle.kts
├── settings.gradle.kts
└── gradle.properties       # SDK versions, library coordinates, POM overrides
```

---

## Building and testing

```bash
# Build and run unit + instrumented tests
./gradlew build

# Run only the build-logic tests
./gradlew :build-logic:check
```

---

## Publishing

Releases are published to Maven Central when a [GitHub Release](https://docs.github.com/en/repositories/releasing-projects-on-github/managing-releases-in-your-repository) is created. The release **tag** defines the version (e.g. tag `v1.0.0` → published version `1.0.0`).

**Prerequisites:** Sonatype Central account, token, and GPG key. Configure the required [GitHub Secrets](.github/workflows/publish-maven-central.yml) and follow the full guide:

👉 **[Publishing to Maven Central](docs/PUBLISHING.md)**

---

## Contributing

Contributions are welcome. Please:

1. Fork the repository.
2. Create a feature branch (`git checkout -b feature/your-feature`).
3. Commit your changes with clear messages (`git commit -m 'Add feature X'`).
4. Push to the branch (`git push origin feature/your-feature`).
5. Open a Pull Request and describe your changes.

---

## License

This project is licensed under the **Apache License 2.0**. See the [LICENSE](LICENSE) file for details.

---

## Contact

- **Issues:** [GitHub Issues](https://github.com/santimattius/android-hilt-extension/issues)
- **Releases:** [GitHub Releases](https://github.com/santimattius/android-hilt-extension/releases)
