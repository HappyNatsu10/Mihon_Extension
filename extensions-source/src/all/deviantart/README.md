# DeviantArt Source Extension for Mihon (v2.6)

A high-performance source extension for Mihon and Tachiyomi forks that allows following DeviantArt artists directly inside Mihon by pasting artwork, profile, or gallery folder links.

---

## Key Features in v2.6

- **One Chapter Per Artwork (Default UX)**:
  - Each artwork produces its own `SChapter` (`name = artwork title`, `url = "/{user}/art/{slug}-{id}"`, and real publish epoch in `date_upload`).
  - Sorted newest first in Mihon's chapter list, matching web and RSS reading expectations.
  - Reader displays uncropped native high-resolution media.
- **Granular Library Entries (All vs Folder)**:
  - Pasting an artwork URL or user profile URL adds the artist's **All Gallery** (`/{user}/gallery/all`).
  - Pasting a folder URL (e.g. `deviantart.com/{user}/gallery/{folderId}`) adds **that specific folder**, allowing users to follow specific series, commissions, or comic chapters separately.
  - Tolerant link resolution: supports URLs without schemes (`deviantart.com/...`), mobile subdomains (`m.deviantart.com`), user subdomains (`{user}.deviantart.com`), and links pasted inside share-sheet text.
- **Permanent Chapter Ledger & Read-History Preservation (`ChapterLedger`)**:
  - Emitted chapter paths are permanently registered in `filesDir/deviantart/ledger_{user}_{folder}.json`.
  - **First-seen path stability:** If an artist edits an artwork's title or slug on DeviantArt, the extension re-emits its first-seen URL, preventing Mihon from treating it as a new chapter or resetting read history.
  - **Superset invariant:** Artworks previously listed that are temporarily missing from fresh crawls (e.g. mature deviations while logged out) are re-emitted from the ledger, ensuring Mihon never deletes them.
  - The ledger survives cache rebuilds, schema upgrades, and "Clear gallery disk cache" actions.
- **Resumable Disk Cache & Time-Budgeted Crawling (`GalleryCache`)**:
  - Solves the 20,000+ artwork scale problem without memory or database lag.
  - Crawls are time-budgeted (4 minutes per refresh) and fully resumable if interrupted.
  - Future refreshes run incrementally: checks `offset = 0` and stops as soon as an already-cached artwork ID is encountered (~1 fast network request).
- **Full-Quality Uncropped Images (`ImageSelector`)**:
  - Renditions are verified against original aspect ratio (within 2% tolerance) to reject cropped thumbnails while preserving full-view renditions.
  - **Best available mode:** Prioritizes uncompressed download files when allowed by the artist.
  - **Standard mode:** Delivers standard web full-view images to save bandwidth.
  - Guarded quality upgrade: Automatically upgrades `q_70` -> `q_100` on supported transforms without touching tokens.
- **Multiple Browse Feeds (`Feeds`)**:
  - **Popular Feed**: Global featured and trending deviations.
  - **Latest Feed (`supportsLatest = true`)**: Real-time newest uploads across DeviantArt.
  - **Followed Deviants Feed**: Access your personal DeviantArt watch feed directly in Mihon by selecting the `Feed -> Latest from followed Deviants` filter (or searching `feed:followed`). Requires logging in via WebView once.
- **HTTP 403 Hardening & Signed Wixmp Integrity (`HttpGuard`)**:
  - Non-tampering image pipeline: Preserves signed Wixmp CDN URLs with exact query parameters and token paths (`token=...`), preventing 403 authorization failures.
  - Distinct error classification with user-actionable error messages (Cloudflare challenge, rate limits, mature content restrictions, token expiry).
  - Exponential backoff retries for transient errors.
- **Configurable Preferences**:
  - Chapter Mode selector: `Each artwork (Default)` vs `Batches (Appendix A)`.
  - Image quality selector: `Best available (Default)` vs `Standard (full view)`.
  - Diagnostic debug logging toggle (logs redacted URLs and headers in Logcat).
  - Clear gallery disk cache button (clears crawl cache while keeping read history ledger).

---

## Installation

1. Build the APK locally using Gradle:
   ```bash
   ./gradlew :src:all:deviantart:assembleDebug
   ```
2. The generated APK is at:
   ```
   src/all/deviantart/build/outputs/apk/debug/tachiyomi-all.deviantart-v1.6.12.apk
   ```
3. Install the APK on your Android device running Mihon.
4. Verify **DeviantArt** is listed under **Browse > Extensions**.

---

## Usage Guide

### 1. Adding Artists or Folders by Link

Copy and paste any DeviantArt link into Mihon's Search bar:
- **Artwork Link**: `https://www.deviantart.com/rossdraws/art/LAST-CHANCE-Shop-Event-996651315` (resolves to `rossdraws - All`)
- **Profile Link**: `https://www.deviantart.com/rossdraws` (resolves to `rossdraws - All`)
- **Specific Folder Link**: `https://www.deviantart.com/rossdraws/gallery/70659618/personal-art` (resolves to `rossdraws - Folder 70659618`)
- **Username shorthand**: `@rossdraws` or `user:rossdraws`
- **Short URL**: `https://fav.me/dghdp2r`

Tap the entry and click **Add to Library**.

### 2. Following Deviants Feed

1. Open any DeviantArt entry and tap **Open in WebView** (compass/globe icon).
2. Log into your DeviantArt account in the WebView.
3. Open **DeviantArt** in Mihon's Browse tab.
4. Open the Filter sheet, set **Feed Source** to **Latest from followed Deviants**, and tap **Filter** (or search `feed:followed`).
5. Your watch feed deviations will load seamlessly.

### 3. Mature Content

DeviantArt requires an authenticated account to view mature/sensitive artworks:
1. Tap **Open in WebView** within Mihon on any DeviantArt artwork or gallery.
2. Sign in to your DeviantArt account once.
3. Mihon's OkHttp client shares cookies with the WebView, enabling full resolution of mature deviations without storing credentials in the extension.

### 4. Device Display Settings Note

If reader pages appear cut or cropped on your device:
- Check **Mihon Settings > Reader > Scale type** (use "Fit screen" or "Smart fit").
- Check **Crop borders** or **Split tall images** if webcomics appear split.

---

## Unit Testing & Verification

Run the test suite with:
```bash
./gradlew :src:all:deviantart:testDebugUnitTest
```
