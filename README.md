# DeviantArt Extension for Mihon / Tachiyomi

A feature-complete Mihon/Tachiyomi source extension for **DeviantArt**, engineered according to [PRD v2.6](./PRD_DeviantArt_Mihon_Extension_v2.6.md).

## Key Features

- **One Chapter Per Artwork (Default)**: Follows the artist's gallery order, named with original deviation titles and publish timestamps.
- **Tolerant Folder Link Resolution (FR-15)**: Paste any artwork, artist profile, or specific gallery folder link (supports scheme-less links, mobile/subdomain URLs, pasted share-sheet text, mixed-case paths).
- **Permanent Chapter Ledger (FR-13)**: Guarantees `ledger ids ⊆ returned ids` across all crawls. Stored in persistent app storage (`filesDir`), preserving read history and bookmarks across cache clears, title changes, and mature/logged-out states.
- **Completeness Invariant (FR-14)**: Zero silent drops. Chapter counts accurately reflect gallery size; non-image deviations remain chapters with clear notices.
- **Uncropped & Full-Quality Renditions (FR-14 & FR-16)**:
  - 2% aspect ratio validation against original dimensions.
  - Distinguishes `/v1/fill/` full views from genuine crops.
  - Prioritizes artist-allowed original downloads in `Best available` mode.
  - Guarded `q_100` quality upgrade on WixMP CDN transform paths.
- **Browse Feeds (FR-7)**: Explore Popular, Latest (`supportsLatest = true`), and "Latest from followed Deviants" (with WebView session detection).

## Project Structure

```text
.
├── PRD_DeviantArt_Mihon_Extension_v2.6.md # Latest product requirement specification
├── PRD_DeviantArt_Mihon_Extension_v2.md   # Prior v2 specification
├── PRD_DeviantArt_Mihon_Extension.md      # Initial v1 specification
└── extensions-source/                     # Mihon/Keiyoushi extension build tree
    ├── docs/                              # Technical investigations and audit logs
    │   ├── API_FINDINGS.md
    │   ├── COMPAT.md
    │   ├── FOLDER_LINKS.md
    │   ├── QUALITY.md
    │   ├── COMPLETENESS.md
    │   └── 403_CASES.md
    └── src/all/deviantart/                # DeviantArt extension source & tests
        ├── build.gradle.kts
        ├── README.md
        ├── src/...                        # Kotlin implementation
        └── test/...                       # Automated JVM test suite
```

## Building the Extension

To run automated unit tests:
```bash
cd extensions-source
./gradlew :src:all:deviantart:testDebugUnitTest
```

To assemble the installable debug APK:
```bash
cd extensions-source
./gradlew :src:all:deviantart:assembleDebug
```

The compiled APK will be output to:
`extensions-source/src/all/deviantart/build/outputs/apk/debug/tachiyomi-all.deviantart-v1.6.12.apk`
