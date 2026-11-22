# Classic Budget Plus

Classic Budget Plus is an independent patch bundle for Morphe. It supports the YNAB 4 Android companion app. Use your own original Android APK for version `3.4.1_classic`. The Releases page carries the `.mpp` bundle. The source builds the patches and their Android extension.

## Add to Morphe Manager

Open Patch sources, add a remote source, and enter the URL of this repository. You can also use the [Add in Morphe Manager](https://morphe.software/add-source?github=ysosrsss/classic-budget-plus-patches) link. The repository URL resolves to `patches-bundle.json`, which points to the current preview.

You can also download the `.mpp` from Releases and import it as a local source. Use the same Morphe installation and signing key as your existing app when applying an update.

## Profiles

Use a separate budget copy with the TEST profile. The production profile is a separate package and uses the original Dropbox budget folder. The first release is a preview. See its release notes for the exact tests completed.

## Build

Use Java 21, Android SDK Platform 36 and Build Tools 36. Configure the SDK through `ANDROID_HOME` or a local `local.properties`. Gradle reads GitHub Packages credentials from the user's Gradle configuration or the standard GitHub Actions environment. Run `gradlew.bat buildAndroid` on Windows or `./gradlew buildAndroid` on other platforms. The bundle is generated under `patches/build/libs`.

## License

The patch code is distributed under GPL-3.0. See [LICENSE](LICENSE), [NOTICE](NOTICE) and [third-party notices](THIRD_PARTY_NOTICES.md). YNAB and Morphe are separate projects.
