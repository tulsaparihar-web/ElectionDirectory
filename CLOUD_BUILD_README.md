# Election Contact Directory — Cloud APK Build

This package is prepared for building the Android APK in GitHub Actions, so Android Studio is not required for the build machine.

## Build steps

1. Create a new GitHub repository (for example `ElectionContactDirectory`).
2. Upload **all files and folders inside this ZIP** to the repository root.
3. Commit the files to the `main` branch.
4. Open the repository's **Actions** tab.
5. Select **Build Election Contact Directory APK**.
6. Click **Run workflow** if it has not already run from the push.
7. Wait for the workflow to finish.
8. Open the completed workflow run.
9. Under **Artifacts**, download **Election-Contact-Directory-APK**.
10. The downloaded ZIP contains the APK.

## Build environment

- Java 17
- Gradle 8.13
- Android SDK Platform 36
- Android Build Tools 35.0.0
- Ubuntu GitHub Actions runner

The workflow deliberately uses Gradle 8.13 directly because this project package does not currently include a Gradle Wrapper. The Gradle setup action supports selecting a specific Gradle version and caching it for subsequent builds.

## Important

This cloud workflow builds the current FIXED7 source. It does not change the application's offline architecture or embedded directory data.

If the cloud build reports a Kotlin/Compose error, the workflow uploads build reports when available so the exact compiler error can be diagnosed without guessing.
