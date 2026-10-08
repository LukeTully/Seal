# Repository Guidelines

## Project Structure & Module Organization
Seal is an Android video/audio downloader built around yt-dlp, using Kotlin and Jetpack Compose.
- `app/src/main/java/com/junkfood/seal/`: application code; `ui/` contains Compose screens and themes, `download/` manages downloads, `database/` contains Room persistence, and `util/` holds helpers.
- `app/src/main/res/`: Android resources, localized strings, and icons. Room schema snapshots live in `app/schemas/`.
- `app/src/test/` and `app/src/androidTest/`: local unit tests and device instrumentation tests.
- `color/`: Android library for dynamic color palettes and Material color utilities.
- `buildSrc/`: build-time version definitions; `gradle/libs.versions.toml`: dependency and plugin versions.
- `translations/`: translated READMEs; `fastlane/metadata/android/`: store descriptions and screenshots.

## Build, Test, and Development Commands
Use JDK 21, Android Studio, and Android SDKs 35 (`app`) and 34 (`color`). Run the Gradle wrapper from the repository root.
- `./gradlew :app:assembleGenericDebug`: build debug APKs under `app/build/outputs/apk/`.
- `./gradlew :app:installGenericDebug`: install on a connected device/emulator; launch Seal Debug locally.
- `./gradlew buildGenericRelease`: run the release build used by pull-request CI.
- `./gradlew :app:testGenericDebugUnitTest`: run JVM unit tests.
- `./gradlew :app:connectedGenericDebugAndroidTest`: run device tests.
- `./gradlew :app:lintGenericDebug`: run Android lint.
- `./gradlew :app:ktfmtFormat :app:ktfmtCheck`: format Kotlin and verify formatting.

## Coding Style & Naming Conventions
Use Kotlin official style with four-space indentation; the app configures ktfmt's `kotlinLangStyle()`. Follow nearby code in `color/`, which does not apply ktfmt. Use PascalCase for classes, Kotlin filenames, and Compose functions; camelCase for other functions and properties; snake_case for Android resource names. Keep UI text in string resources and place code in its feature package.

## Testing Guidelines
Local tests use JUnit 4; device tests use AndroidJUnit4 and Espresso. Name test classes `*Test` and use descriptive test methods. Existing coverage is sparse, with no enforced percentage. Add regression tests for changed behavior. Check instrumentation package assertions against the selected variant's application ID; the existing sample assertion is outdated.

## Commit & Pull Request Guidelines
Prefer the history's `type(scope): summary` convention, for example `fix(download): handle empty playlists`. Before contributing code, describe the proposed change on a relevant issue and claim the work, as required by `CONTRIBUTING.md`. Link that issue in the PR, explain behavior changes, list validation performed, and include screenshots for UI changes. Follow `CODE_OF_CONDUCT.md`.

## Configuration & Secrets
Keep SDK paths in untracked `local.properties`. Never commit signing keys or `keystore.properties`; release signing is configured only when that file exists. Include updated Room schema snapshots when changing the database.
