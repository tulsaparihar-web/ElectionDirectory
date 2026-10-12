# Rajasthan BLO Directory — GitHub APK build

A dedicated GitHub Actions workflow has been added without replacing the existing Election Directory app.

## One-time upload needed
The GitHub connector can commit text files, but cannot upload the locally generated ZIP as a binary repository file. Please upload the project ZIP once using GitHub's website:

1. Open the repository: https://github.com/tulsaparihar-web/ElectionDirectory
2. Choose **Add file → Upload files**.
3. Upload `RajasthanBLODirectory_Project.zip` to the repository root (not inside `app/`).
4. Commit the upload to `main`.

The workflow will start automatically when that ZIP is added. It extracts the project, installs Java/Android SDK/Gradle, builds the debug APK, and publishes it as a downloadable GitHub Actions artifact.

## Download the APK
Open **Actions → Build Rajasthan BLO Directory APK**, choose the successful run, then download the **Rajasthan-BLO-Directory-APK** artifact. Extract the artifact ZIP to get `app-debug.apk`.

This creates a debug APK for testing. A production release APK needs release signing and security/data verification.
