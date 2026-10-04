# DocSwipe — Implementation Specification

Version: 1.0  
Status: Product decisions locked; implementation-ready  
Application: DocSwipe  
Package: `com.mag.docswipe`  
Platform: Android, portrait-only  
Distribution: Sideloaded APK for personal use

## 1. Objective

DocSwipe is an offline Android application for reviewing local documents in a Tinder-like interface. It lets a user inspect document contents, keep files, stage files for permanent deletion, skip uncertain files, undo recent actions, and resolve byte-identical duplicates.

The app must optimize for a Pixel 6a and Android emulator first, while remaining compatible with Android 14 and later.

The app must never upload files, metadata, passwords, hashes, telemetry, or browsing data. All scanning, rendering, duplicate detection, and deletion work is local.

## 2. Locked product decisions

### Platform and build

- Visible name: `DocSwipe`.
- Package name: `com.mag.docswipe`.
- Kotlin and Jetpack Compose.
- Minimum Android version: API 34 (Android 14).
- Compile/target SDK: latest stable SDK available in the development environment; initially API 36 where available.
- Portrait orientation only.
- Sideloading first; Google Play compliance is out of scope for v1.
- APK size is not a product constraint. Correct document rendering and user experience take priority.
- Dependencies must be open-source and freely redistributable.

### File scope

Supported extensions:

`pdf`, `docx`, `xlsx`, `pptx`, `txt`, `csv`

Exclude:

- zero-byte files;
- unknown extensions;
- unsupported MIME types;
- Android/system folders;
- unreadable files;
- symbolic links and filesystem shortcuts;
- files outside the user-accessible local storage scope.

The scanner may include user-created hidden folders only when the user enables that setting.

### First-run permissions

First launch must:

1. Explain why storage access is required.
2. Request the required local-storage access.
3. After access is granted, ask: `Scan hidden folders?` with `Yes` and `No` actions.
4. Default to `No`.
5. Start the first scan automatically after the choices are made.

Settings must allow the user to change hidden-folder scanning later. Changing this preference must trigger a rescan or clearly offer one.

### Date grouping

- Normal grouping uses the file's last-modified timestamp.
- The default order is oldest modified month first.
- The user can switch between oldest-first and newest-first.
- A month is represented as a DocSwipe review bucket, not a physical folder.
- Files are never moved or renamed on device storage.

### Duplicate grouping

Only byte-identical files may be marked duplicates.

Duplicate verification must end with a full SHA-256 comparison. Size and sparse hashes are optimization filters only and are never sufficient to declare a duplicate.

The oldest modified byte-identical file is initially the original. Every duplicate remains an individual card, but duplicate cards are shown in the original's DocSwipe review bucket regardless of the duplicate file's own modified month.

If the original is deleted and another duplicate remains, the oldest remaining file becomes the new original automatically. This is a metadata reassignment only; no files are moved.

### Review states

Each document has one triage state:

- `UNREVIEWED`: appears in the normal deck.
- `KEEP`: user chose to retain it.
- `STAGED_DELETE`: user selected it for deletion but deletion has not run.
- `SKIPPED`: user deferred the decision.
- `DELETED`: physical deletion succeeded; normally removed from the active document table.
- `DELETE_FAILED`: deletion failed; retained in a separate failed-deletion section.

The normal month deck contains unreviewed files. Skipped files return after the normal deck finishes if the user chooses to review them. A month remains visible as `Pending review` while skipped or unresolved files remain.

### Deletion behavior

- Left swipe and Delete button set `STAGED_DELETE`.
- No physical deletion occurs during swiping.
- After review, the user sees the deletion review screen.
- The review screen shows selected files and total reclaimable bytes.
- The user can restore individual staged files.
- The user can permanently delete the staged set without an additional Android trash step.
- Deletion is attempted once per file and continues through the entire batch.
- Partial success is valid.
- The result shows total attempted, successfully deleted, and failed counts.
- Failed files show their full path and remain available in the home-screen `Failed Deleted Files` section.
- Failed files have a retry action. Retrying is an explicit user action and again attempts deletion once.
- No general deletion history is required.

## 3. Core user experience

### Home screen

The home screen contains:

1. A list of month buckets, oldest-first by default.
2. Each bucket displays:
   - month label, such as `July 2026`;
   - remaining document count;
   - total size;
   - pending-review badge when skipped items remain;
   - staged-deletion count when relevant.
3. A sort control for oldest-first/newest-first.
4. A collapsed bottom section named `Failed Deleted Files (N)`.
5. A rescan/pull-to-refresh action.
6. Settings access.

Completed months disappear only when they contain no unreviewed, skipped, staged, or failed items.

If a later scan finds a new or changed file belonging to a completed month, that month reopens automatically.

### Deck screen

The deck screen contains:

- current month label;
- progress count;
- optional thumbnail/format rail;
- duplicate badge where applicable;
- document viewer as the main card;
- four action buttons: Undo, Skip, Delete, Keep;
- Review Month action.

Actions:

- Left swipe: stage deletion.
- Right swipe: keep.
- Skip button: place at the end of the current review queue as `SKIPPED`.
- Undo: restore the immediately previous triage state.
- Vertical movement inside the document viewer: scroll document content.
- Pinch gesture inside the viewer: zoom document content.

Horizontal swipe recognition must work from anywhere on the card. Vertical scrolling must be owned by the document viewer. At the first or last page, further vertical movement must not dismiss the card; show only a subtle edge/boundary animation.

### Skipped-file flow

When the normal deck is exhausted and skipped files exist, show a decision screen:

`You skipped N files. Review them now?`

Actions:

- `Review skipped files`: show the skipped files as a second queue.
- `Leave for later`: return to the home screen; the month remains `Pending review`.

When skipped files are reviewed, they become `KEEP` or `STAGED_DELETE`. If the user skips them again, they remain pending.

### Deletion review

The deletion review screen shows:

- every staged file;
- file name;
- source path;
- format;
- size;
- duplicate/original relationship where applicable;
- total count;
- total bytes;
- tap-to-restore action.

The primary action is `Delete Selected`. There is no second confirmation dialog because the in-app review screen is the confirmation boundary.

### Deletion result

Show:

- files attempted;
- files successfully deleted;
- files that failed;
- bytes reclaimed;
- failed file paths;
- retry option for failures.

The result screen must not hide failures. A failed item remains in the failed-deletion table and is visible from the home screen.

## 4. Document rendering requirements

The rendering layer must expose a common document-viewer contract while using format-specific implementations.

### PDF

- Support encrypted/password-protected PDFs.
- Request a password inline when needed.
- Keep entered passwords in memory only.
- Clear the in-memory password cache when the app process ends.
- A short session TTL may be used, with 30 minutes as the initial value.
- Never write PDF passwords to Room, preferences, logs, crash reports, or files.
- Render pages lazily.
- Keep approximately ten pages actively rendered at a time; use a small configurable cache around the visible range.
- Do not render the entire document into memory.
- Support pinch-to-zoom.
- Support long documents without an artificial fifteen-page document limit.
- Use bitmap-size and memory safeguards to prevent OOM crashes.

The PDF engine must provide:

- total page count;
- page rendering at a requested scale;
- password-required state;
- recoverable rendering errors;
- cancellation when the card changes;
- bitmap/resource cleanup.

### DOCX

The viewer must preserve useful visual structure, including where feasible:

- paragraphs;
- headings;
- bold/italic emphasis;
- tables;
- images;
- basic spacing and page structure.

Plain XML text extraction alone is not acceptable for the final viewer. If an open-source renderer cannot provide full layout, the implementation must document the supported subset and degrade visibly rather than silently presenting misleading content.

### XLSX

The viewer must support, where feasible:

- multiple sheets;
- cell values;
- basic formatting;
- column widths;
- row heights;
- borders and fills;
- merged cells;
- formulas as displayed values when cached values exist;
- images/charts if supported by the chosen engine.

Horizontal scrolling is not part of the initial interaction contract. The viewer should fit or scale wide content to the portrait viewport and allow pinch zoom.

### PPTX

The viewer should preserve:

- slide boundaries;
- text placement;
- images;
- backgrounds;
- basic shapes and layout.

Slides may be rendered as page-like images or composited native views, depending on the selected open-source engine. Text-only extraction is not acceptable.

### TXT and CSV

- Portrait-only vertical viewer.
- No horizontal scrolling in v1.
- Pinch zoom supported.
- CSV rows and columns should remain visually distinguishable.
- Long files must be streamed or paged rather than loaded entirely into memory.

### External viewer fallback

Unsupported or failed rendering must not appear as a supported document card in v1. The scanner should exclude formats that cannot be opened by the selected renderer contract. A future version may offer external-viewer fallback.

## 5. Storage scanning

The scanner must be cancellable, resumable at the application level, and run off the main thread.

Scan roots should be derived from Android-accessible storage rather than hardcoding a single path as the only source.

Exclude:

- `/Android` and its descendants;
- known system/runtime/cache directories;
- hidden directories when the preference is disabled;
- symbolic links;
- unreadable directories;
- files with unsupported extensions;
- files of size zero.

For each accepted file, collect:

- stable document identifier;
- URI/path reference;
- display name;
- extension;
- MIME type;
- byte size;
- last-modified timestamp;
- month bucket;
- scan timestamp;
- renderer capability;
- filesystem identity information where available.

### Rescan policy

- First launch: full scan.
- Manual pull-to-refresh: full/delta scan according to current implementation state.
- App reopen: use cached Room data for fast startup.
- If the cache is older than one hour, run a delta scan after showing cached data.
- A changed file must have its triage state reconsidered according to the product rules; do not silently preserve a stale decision when the file contents or modification timestamp changed.
- A missing file must be reconciled from the database.

## 6. Duplicate detection

Pipeline:

1. Group candidate files by exact byte size.
2. Read sparse chunks from candidates, such as beginning, middle, and end.
3. Group matching sparse fingerprints.
4. Compute full SHA-256 only for sparse matches.
5. Mark duplicates only when full hashes match.

The implementation must handle:

- files changing during hashing;
- files disappearing during hashing;
- read permission failures;
- very large files;
- cancellation;
- stale duplicate groups after rescans.

Duplicate data should be normalized into a group record or an equivalent relational representation. Do not encode business meaning only in a fragile string field.

The duplicate group must expose:

- full hash;
- current members;
- current original member;
- member modified dates;
- member paths;
- member triage states.

When a member is deleted, recompute the original from remaining members by oldest modified timestamp, with a deterministic path/ID tie-breaker.

## 7. Data model

The exact schema may be adjusted during implementation, but it must represent the following concepts:

### Documents

- `id`
- `uriOrPath`
- `displayName`
- `extension`
- `mimeType`
- `sizeBytes`
- `modifiedAt`
- `monthBucket`
- `triageStatus`
- `duplicateGroupId`, nullable
- `isCurrentOriginal`
- `rendererType`
- `isPasswordProtected`, nullable/unknown until inspected
- `lastSeenScanId`
- `createdAt`
- `updatedAt`

### Duplicate groups

- `id`
- `fullSha256`
- `originalDocumentId`
- `memberCount`
- `updatedAt`

### Failed deletions

- `id`
- `documentId` or last-known document identity
- display name
- URI/path
- size
- failure reason
- first failure timestamp
- last retry timestamp
- retry count
- current status

### Scan state

- last scan start/end time;
- scan mode;
- hidden-folder preference;
- scan error state;
- cancellation state.

Use Room with migrations from the beginning. All destructive database updates must be transactional.

## 8. Gesture architecture

Gesture handling must use explicit directional locking:

1. Start with an undetermined lock.
2. Wait for touch slop.
3. Compare horizontal and vertical displacement.
4. If horizontal dominance exceeds the configured threshold, lock to card swipe.
5. Otherwise, lock to the document viewer.
6. Never change lock type during the same gesture.

The exact ratio must be tuned on the Pixel 6a and emulator. The previous 1.35 ratio is an initial value, not a correctness guarantee.

Acceptance requirements:

- normal vertical PDF scrolling never dismisses the card;
- normal horizontal swipes never scroll the document;
- diagonal gestures resolve consistently;
- edge overscroll does not triage the document;
- buttons and gestures produce the same state transitions;
- undo restores the previous state exactly.

## 9. Architecture and project structure

Use a small Clean Architecture layout without speculative abstractions:

```text
docswipe/
├── app/
│   ├── build.gradle.kts
│   └── src/main/
│       ├── AndroidManifest.xml
│       └── java/com/mag/docswipe/
│           ├── MainActivity.kt
│           ├── DocSwipeApplication.kt
│           ├── core/
│           │   ├── AppSettings.kt
│           │   ├── FileTypes.kt
│           │   ├── Result.kt
│           │   └── TimeBuckets.kt
│           ├── data/
│           │   ├── local/
│           │   │   ├── DocSwipeDatabase.kt
│           │   │   ├── DocumentDao.kt
│           │   │   ├── DuplicateDao.kt
│           │   │   ├── FailedDeletionDao.kt
│           │   │   └── entities/
│           │   ├── scanner/
│           │   ├── duplicate/
│           │   ├── deletion/
│           │   └── rendering/
│           │       ├── DocumentRenderer.kt
│           │       ├── PdfRenderer.kt
│           │       ├── OfficeRenderer.kt
│           │       ├── TextRenderer.kt
│           │       └── CsvRenderer.kt
│           ├── domain/
│           │   ├── model/
│           │   ├── repository/
│           │   └── usecase/
│           └── presentation/
│               ├── navigation/
│               ├── home/
│               ├── deck/
│               ├── review/
│               ├── deletionresult/
│               ├── settings/
│               ├── components/
│               └── theme/
├── app/src/test/
├── app/src/androidTest/
└── README.md
```

Do not introduce separate modules, a repository interface with no realistic second implementation, or a dependency-injection framework solely for ceremony. Add those only when the implementation demonstrates a need.

## 10. Implementation phases

### Phase 0 — Environment and rendering spike

Deliverables:

- empty Android project;
- package and app identity;
- portrait lock;
- emulator launch;
- Pixel 6a install path;
- one sample file for every supported format;
- renderer evaluation for PDF, DOCX, XLSX, and PPTX.

Exit criteria:

- all selected renderer dependencies are open-source and redistributable;
- sample files open with acceptable visual fidelity;
- APK size and memory use are measured;
- no renderer is accepted merely because it extracts text.

### Phase 1 — Permissions and scanner

Deliverables:

- first-run explanation;
- storage permission flow;
- hidden-folder Yes/No prompt;
- settings toggle;
- cancellable scanner;
- supported-file filtering;
- system-folder exclusion;
- Room persistence;
- home timeline with real scan data.

Exit criteria:

- scan works on emulator and Pixel 6a;
- zero-byte and unsupported files are absent;
- hidden-folder preference changes behavior;
- Android/system folders are excluded;
- first launch automatically begins scanning after permission.

### Phase 2 — Rendering viewer

Deliverables:

- document renderer contract;
- PDF lazy pages and passwords;
- Office visual renderer;
- TXT/CSV portrait viewer;
- pinch zoom;
- loading, empty, and renderer-error states;
- cancellation and resource cleanup.

Exit criteria:

- a document can be read inside the card;
- long PDFs do not load every page at once;
- protected PDFs can be unlocked without persistent password storage;
- supported Office samples preserve meaningful visual structure.

### Phase 3 — Deck and gesture engine

Deliverables:

- month deck;
- card stack;
- horizontal/vertical gesture arbitration;
- delete/keep/skip/undo buttons;
- edge-scroll feedback;
- progress and duplicate badges.

Exit criteria:

- 60 FPS is the target on Pixel 6a for ordinary cards;
- horizontal and vertical gestures do not interfere;
- button and gesture behavior are identical;
- undo works across the latest actions.

### Phase 4 — Duplicates

Deliverables:

- size grouping;
- sparse fingerprinting;
- full SHA-256 verification;
- duplicate groups;
- original selection;
- cross-month grouping into the original's review bucket;
- individual duplicate cards;
- keep-original/delete-duplicates action;
- original reassignment after deletion.

Exit criteria:

- byte-identical files are grouped;
- non-identical files are never marked duplicates;
- duplicates across months appear together;
- deleting the original reassigns the oldest remaining member.

### Phase 5 — Review, deletion, and failures

Deliverables:

- staged deletion review;
- restore action;
- transactional batch execution;
- partial success handling;
- failed-deletion table;
- home-screen failed section;
- retry flow;
- path display.

Exit criteria:

- no file is deleted during swiping;
- deletion proceeds after the review action;
- one failure does not stop later files;
- success/failure counts are accurate;
- failed files remain actionable;
- successful deletions disappear from active review data.

### Phase 6 — Rescan and polish

Deliverables:

- one-hour cache policy;
- pull-to-refresh;
- changed-file reconciliation;
- reopened completed months;
- settings polish;
- accessibility labels;
- performance profiling;
- release APK.

Exit criteria:

- cached startup is fast;
- new files reopen the correct bucket;
- changed files are not silently misclassified;
- no passwords or document contents appear in logs;
- release APK installs and runs on the Pixel 6a.

## 11. Test plan

### Scanner tests

- supported extensions accepted;
- unsupported extensions rejected;
- zero-byte files rejected;
- hidden-folder preference respected;
- Android/system folders excluded;
- unreadable folders do not crash scanning;
- symbolic links are not followed;
- changed and deleted files reconcile correctly.

### Duplicate tests

- identical files with different names match;
- identical files across months match;
- same-size but different files do not match;
- sparse-hash collisions require SHA-256 confirmation;
- original selection is deterministic;
- original reassignment works after deletion.

### State tests

- keep, delete, skip, and undo transitions;
- skipped queue returns after the normal queue;
- pending month remains visible;
- completed month disappears only when fully resolved;
- duplicate actions stage the intended members.

### Deletion tests

- all files deleted successfully;
- one failure among successful deletions;
- all deletions fail;
- retry succeeds;
- retry fails again;
- staged files can be restored before deletion;
- no deletion occurs before the review action.

### Rendering tests

- small and large PDFs;
- encrypted PDFs;
- long PDFs with lazy loading;
- DOCX with headings, tables, and images;
- XLSX with multiple sheets, formulas, and wide content;
- PPTX with images and text;
- long TXT/CSV files;
- renderer cancellation when swiping to another card;
- memory pressure and bitmap cleanup.

### Device tests

- Android emulator API 34;
- current emulator API;
- Pixel 6a;
- slow storage simulation;
- low-memory simulation;
- thousands of indexed documents;
- large individual documents.

## 12. Non-goals for v1

- Google Drive, OneDrive, Dropbox, or other cloud sources;
- OCR or machine learning classification;
- iOS support;
- landscape orientation;
- Android trash/recycle-bin integration;
- deletion history or restore history;
- near-duplicate or visually similar matching;
- background automatic scanning without user interaction;
- file movement or renaming;
- unsupported document types;
- external viewer fallback as a required path.

## 13. Implementation rules for the coding agent

1. Work phase-by-phase; do not build the entire app before validating the renderer and storage spikes.
2. Keep all document/file operations off the main thread.
3. Treat every file operation as fallible.
4. Never log passwords, document contents, or full sensitive paths in release builds.
5. Do not declare a file duplicate without full SHA-256 confirmation.
6. Do not physically delete a file during a swipe.
7. Do not hide failed deletions.
8. Do not move or rename user files.
9. Prefer Android-native APIs and the smallest open-source dependency that satisfies the renderer requirement.
10. If a dependency cannot provide the required Office fidelity, document the limitation and evaluate the next open-source option before accepting a text-only fallback.
11. Add tests for every non-trivial scanner, duplicate, state, deletion, and parsing path.
12. Keep the app usable with an empty storage directory, denied permission, cancelled scan, missing file, corrupted document, protected document, and partial deletion failure.

## 14. Definition of done for the first stable release

The first stable release is complete when a user can:

1. Install the sideloaded APK on the emulator and Pixel 6a.
2. Grant storage access and choose whether hidden folders are scanned.
3. See locally supported documents grouped into month buckets by last-modified date.
4. Open and inspect supported PDF, Office, TXT, and CSV files inside the app.
5. Scroll, zoom, keep, delete-stage, skip, and undo documents.
6. Review skipped documents later.
7. Detect byte-identical duplicates with cross-month grouping.
8. Keep the oldest original and stage duplicates for deletion.
9. Review staged files before physical deletion.
10. Permanently delete files in a batch with partial failure handling.
11. See failed deletions and their paths from the home screen.
12. Retry failed deletions explicitly.
13. Rescan and see new files reopen the appropriate month.
14. Use the app fully offline.

