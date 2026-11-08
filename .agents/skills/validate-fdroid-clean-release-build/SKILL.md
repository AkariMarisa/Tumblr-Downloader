---
name: "validate-fdroid-clean-release-build"
description: "Validate that Tumblr Downloader release builds work in F-Droid's clean, unsigned environment."
version: 1
created: "2026-08-27"
updated: "2026-09-26"
---
## When to Use
Use before submitting or updating the Tumblr Downloader F-Droid metadata, or whenever Gradle release signing changes.

## Procedure
1. Check the release tag for tracked JKS files: git ls-tree -r --name-only <tag> -- app/release.jks.
2. Run an F-Droid-like release build with a deliberately absent keystore: KEYSTORE_PATH=/tmp/fdroid-no-keystore.jks ./gradlew :app:assembleRelease --console=plain.
3. In app/build.gradle.kts, create and apply the release signing configuration only when the selected keystore is an existing file. This leaves F-Droid an unsigned release APK to sign itself.
4. Run ./gradlew testDebugUnitTest --console=plain after the Gradle change.
5. Before updating fdroiddata, bump versionCode/versionName, commit and push the source, create and push an immutable v<version> tag. When manually adding a build to metadata, retain all historical `Builds`, append the new version in ascending `versionCode` order with a blank line between entries, then update `CurrentVersion` and `CurrentVersionCode`.
6. In fdroiddata, run `fdroid lint <package-name>` and `fdroid rewritemeta <package-name>`; check the resulting diff and ensure rewritemeta makes no further changes before submitting.

## Pitfalls
- A developer's local app/release.jks can hide a F-Droid failure; the keystore is not tracked at v1.0.4.
- Do not configure release signing unconditionally: Android Gradle's validateSigningRelease fails before producing an APK when the specified keystore is absent.
- F-Droid metadata must reference an immutable, pushed Git tag and matching versionCode; do not point it at an untagged master commit.
- Do not replace previous `Builds` when adding a release; `rewritemeta` requires old-to-new ordering and a blank line between entries.

## Verification
1. The forced-missing-keystore assembleRelease exits 0 and does not run validateSigningRelease with a missing file.
2. The full debug unit-test suite exits 0.
3. The release tag contains the source changes and the F-Droid metadata commit uses that exact tag or full commit SHA.
4. The metadata retains previous builds, places the new build last, and has no remaining `fdroid rewritemeta` diff.
