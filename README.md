# DocSwipe

DocSwipe is a private, offline-first Android app for cleaning up downloaded documents safely. It groups documents by their last-modified month and presents them as a focused review deck: swipe left to stage a file for deletion, swipe right to keep it, scroll up or down to read it, or skip it for a later decision.

DocSwipe is currently intended for personal side-loading and testing. It does not upload documents to a server and does not require an account.

## Product overview

DocSwipe helps answer a simple question: “Which documents on my phone still need to be kept?”

The app scans supported document files, groups them by last-modified month, and lets the user review them without moving or reorganizing the actual files on the device. The grouping and review state exist only inside DocSwipe’s local database.

### Main capabilities

- Month-based document timeline using last-modified dates.
- Oldest-first and newest-first sorting, plus sorting by file count.
- Swipe-based review deck:
  - left: stage the document for deletion;
  - right: keep the document;
  - up/down: read and scroll;
  - skip: defer the decision.
- Multi-level undo through the current deck.
- Skipped files return for a later review pass.
- Review-completion dialog before staged files are permanently deleted.
- Partial deletion results showing successful and failed deletions.
- Retry support for failed deletions, including the original file path.
- Exact duplicate detection using SHA-256 hashes.
- The oldest byte-identical file is labelled `ORIGINAL`; every duplicate remains an individual review card.
- If the original is deleted, the oldest remaining duplicate becomes the new original.
- Hidden-folder scanning is off by default and is requested only during first launch; it can be changed in Settings.
- Android/system directories such as `Android`, `data`, and `obb` are excluded.
- Zero-byte files, unknown formats, and symbolic links are excluded.
- Portrait orientation only.

## Supported formats

- PDF, including password prompts and display-sized rendering to avoid oversized bitmap crashes.
- DOCX, XLSX, and PPTX with in-app offline rendering.
- TXT and CSV with in-app text preview and scrolling.
- EPUB ebooks with chapter-by-chapter in-app reading.
- CBZ comic books with in-app page scrolling.

CBR, MOBI, AZW, and AZW3 are not included yet because they require additional archive or ebook parsers. They remain excluded from scanning until they can be rendered reliably inside the app.

Office rendering is an offline HTML-based rendering path, not Microsoft Word or Excel’s own layout engine. Complex Office documents containing many floating text boxes, custom fonts, layered shapes, or unusual Word-specific layout features may not match the original application perfectly.

## Privacy and deletion model

DocSwipe scans local storage only. Documents are not sent to a cloud service.

Deletion is permanent: DocSwipe does not use Android’s Trash mechanism. A file is deleted only after the user stages it and confirms the deletion flow. If deletion fails, the app records the failure and removes the file from the active review references so the rest of the deck can continue.

## Requirements

- Android 14 or newer.
- Portrait mode.
- Package name: `com.mag.docswipe`.
- Java 17 and Android SDK 35 for local development.

## Install the latest APK

The latest public debug APK is available from the rolling GitHub prerelease:

[Download the latest DocSwipe APK](https://github.com/mahakg290399/docswipe/releases/download/latest/app-debug.apk)

On the phone, allow installation from the source used to open the APK, then install it. This is a debug build intended for personal testing, not a Google Play release.

## Build locally

Open the repository in Android Studio and run the `app` configuration on an Android 14+ emulator or physical device.

From a machine with Java 17 and Android SDK 35 installed:

```bash
./gradlew testDebugUnitTest
./gradlew assembleDebug
```

The debug APK is written to:

```text
app/build/outputs/apk/debug/app-debug.apk
```

## Tests and continuous integration

The GitHub Actions workflow runs on every push, pull request, and manual dispatch. It:

1. installs the Android build toolchain;
2. runs `testDebugUnitTest`;
3. builds the debug APK;
4. uploads the APK as a 30-day workflow artifact;
5. on pushes to `main`, updates the public rolling `latest` prerelease.

Workflow file: `.github/workflows/android.yml`

[View GitHub Actions](https://github.com/mahakg290399/docswipe/actions)

Current unit tests cover document-format acceptance, zero-byte and unknown-file exclusion, and hidden/system-directory rules.

## Why the APK is large

The current debug APK is approximately 363 MB. The main reason is the embedded native Office renderer, not the Compose UI:

- OpenDocument renderer, arm64: approximately 79 MB.
- OpenDocument renderer, x86_64: approximately 76 MB.
- OpenDocument renderer, ARMv7: approximately 67 MB.
- OpenDocument renderer, x86: approximately 66 MB.
- PDF renderer native libraries: approximately 15 MB total.
- Kotlin/Android bytecode: approximately 57 MB before release shrinking.

The debug APK includes native libraries for physical ARM phones and emulator x86 architectures at the same time. It also does not use release shrinking.

## APK size-reduction plan

The most practical reductions are:

### 1. Build a release APK and enable shrinking

Release builds can use R8 code shrinking and resource shrinking. This reduces Kotlin/Java bytecode and unused resources, but it will not remove the large native Office renderer libraries.

### 2. Build an arm64-only APK for personal side-loading

The Pixel 6a uses `arm64-v8a`. An arm64-only APK removes the x86, x86_64, and ARMv7 copies of the native libraries. Based on the current APK contents, this is the largest immediate reduction for personal use.

The trade-off is that the APK would no longer install on x86 emulators or older 32-bit ARM devices. We can publish separate ABI APKs if broader side-loading support is needed.

### 3. Use an Android App Bundle for Google Play later

When DocSwipe moves to Google Play, an `.aab` bundle is the preferred approach. Google Play generates device-specific split APKs, so a user receives only the CPU architecture and resources required by their device. See the [Android App Bundle documentation](https://developer.android.com/guide/app-bundle/app-bundle-format).

### 4. Move Office rendering to an on-demand feature

The Office renderer could eventually be placed in an Android dynamic feature module and downloaded only when the user first opens an Office document. This is primarily a Google Play/Play Feature Delivery solution; it does not work as a simple standalone APK download from GitHub. See [Play Feature Delivery](https://developer.android.com/guide/playcore/feature-delivery).

### 5. Download the renderer independently on first use

For side-loading, the app could download a signed renderer package the first time DOCX/XLSX/PPTX is opened. This would reduce the initial APK, but adds network dependency, download verification, update handling, storage management, and a more complicated native-library loading path. It also means Office files would not be available immediately offline.

## Recommended approach for DocSwipe

For the current personal-use phase:

1. Keep the current embedded functionality.
2. Add a proper release build with R8/resource shrinking.
3. Publish an arm64-only APK for the Pixel 6a and most modern physical phones.
4. Keep a universal debug APK only for development and emulator testing.
5. Revisit App Bundles and on-demand Office delivery when preparing for Google Play.

This should reduce the personal-use download substantially without removing Office support or introducing a network requirement.

## License

DocSwipe is currently a personal-use, freely redistributable project. Third-party dependencies retain their own licenses. The embedded OpenDocument renderer is distributed under MPL-2.0.
