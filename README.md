# DocSwipe

DocSwipe is an offline Android document triage app for reviewing, keeping, skipping, and permanently deleting local documents.

- Android 14+ / portrait-only
- package `com.mag.docswipe`
- local storage permission explanation and hidden-folder preference
- month grouping by last-modified time
- supported-file filtering
- exact duplicate grouping with SHA-256 confirmation
- Compose home timeline and swipe deck
- PDF preview with password handling and display-sized rendering
- In-app DOCX/XLSX/PPTX rendering plus TXT/CSV preview
- keep, skip, delete-stage, undo, review, permanent deletion, partial-failure tracking, and retry

## Build locally

Open the repository in Android Studio and run the `app` configuration on an Android 14+ emulator or device.

From a machine with Android SDK 35 and Java 17 installed:

```bash
./gradlew testDebugUnitTest
./gradlew assembleDebug
```

The APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

## GitHub downloads and CI

Every push and pull request runs the unit tests and debug build through GitHub Actions. Each successful run uploads a 30-day workflow artifact.

Pushes to `main` also update the public rolling `latest` prerelease with the APK:

<https://github.com/mahakg290399/docswipe/releases/tag/latest>
