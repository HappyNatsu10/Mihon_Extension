# DeviantArt Extension Library Compatibility (`COMPAT.md`)

This document records the entry URL shapes, chapter URL formats, cache schema versions, and migration guarantees across extension versions to preserve Mihon library entries and read histories.

---

## 1. Stable Source Identity (FR-12 item 1)

Mihon derives internal source IDs deterministically from:
- `extName`: `"DeviantArt"` (never change)
- `lang`: `"all"` (never change)
- `pkg`: `eu.kanade.tachiyomi.extension.all.deviantart` (never change)

All builds must remain signed with the same keystore, and `versionCode` must increment monotonically on every release (v2.6 uses `versionCode = 12`).

---

## 2. Library Entry URL Shapes (FR-12 item 2 & FR-15)

The canonical entry URL builder is `entryUrl(ref: GalleryRef)` and the canonical reader is `normalizeEntryUrl(stored: String): GalleryRef?`.

The following stored URL shapes are recognized and preserved across updates:

| Stored Entry URL Shape | Resolved Target | Notes |
|------------------------|-----------------|-------|
| `/{user}/gallery/all` | `GalleryRef(user, null)` | Current canonical All gallery shape |
| `/{user}/gallery/{folderId}` | `GalleryRef(user, folderId)` | Current canonical Folder gallery shape |
| `/{user}/gallery` | `GalleryRef(user, null)` | Legacy All gallery shape |
| `/{user}` | `GalleryRef(user, null)` | Legacy profile URL shape |
| `https://www.deviantart.com/{user}/gallery/all` | `GalleryRef(user, null)` | Full URL shape |
| `https://www.deviantart.com/{user}/gallery/{folderId}` | `GalleryRef(user, folderId)` | Full URL folder shape |

---

## 3. Chapter URL Format Stability (FR-12 item 4 & FR-13 item 1)

Mihon associates read status, bookmarks, and reading history with `SChapter.url`. If a chapter's URL changes on DeviantArt or between extension builds, Mihon treats the old chapter as deleted (wiping history) and the new URL as unread.

### Guaranteed Emitted Format:
- Single artwork: `/{user}/art/{slug}-{id}` or `/deviation/{id}`.
- First-seen path preservation: Stored in `ChapterLedger` (`ledger_{user}_{folder}.json`) and reused permanently across updates, even if the artist alters the artwork title or slug on DeviantArt.

---

## 4. Chapter Ledger & Superset Invariant (FR-13 items 7–10)

To guarantee that read history is never lost:
1. Every emitted artwork is permanently recorded in `filesDir/deviantart/ledger_{user}_{folder}.json`.
2. The ledger is **never deleted**, even when the user clicks "Clear gallery disk cache".
3. **Superset Invariant:** If an artwork was previously listed but is missing from a subsequent crawl (e.g., mature deviation when logged out, private, or deleted), it is re-emitted from the ledger so Mihon never deletes the chapter from its database.

---

## 5. Cache Schema History

- `schemaVersion = 1`: Persistent disk cache located at `filesDir/deviantart/{user}_{folder}.json`, storing `CachedItem` records with incremental crawl offsets and 4-minute time budgeting.
