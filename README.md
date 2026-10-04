# DocSwipe

DocSwipe is an offline Android document triage app. The current implementation is the first runnable vertical slice:

- Android 14+ / portrait-only
- package `com.mag.docswipe`
- local storage permission explanation and hidden-folder preference
- month grouping by last-modified time
- supported-file filtering
- exact duplicate grouping with SHA-256 confirmation
- Compose home timeline and swipe deck
- PDF first-page preview and TXT/CSV preview
- keep, skip, delete-stage, undo, review, permanent deletion, partial-failure tracking, and retry

## Build

Open the repository in Android Studio and run the `app` configuration on an Android 14+ emulator or device. The workspace currently has no Android SDK or Gradle installation available to the agent, so APK compilation still needs to be performed in Android Studio or a machine with the Android toolchain installed.

## Current implementation boundary

Office files are discovered and included in the data model, but the viewer currently uses the lightweight text-preview path. Full visual DOCX/XLSX/PPTX rendering is the next implementation spike and should be added behind the renderer boundary before calling the app stable.
