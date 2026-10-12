# DeviantArt Gallery Completeness Specification (`COMPLETENESS.md`)

This document details the gallery completeness guarantees and pagination design per FR-14.

---

## 1. Completeness Invariant (Zero Silent Drops)

- The number of chapters emitted equals the total number of items returned by DeviantArt across all gallery pages.
- Non-image deviations (literature, journals, text posts) are **never dropped** from the chapter listing. They are emitted as chapters so the chapter count matches the artist's gallery. If opened in the reader, they display:
  `"This deviation is a text/literature post and cannot be viewed as an image"`.
- Mature-gated deviations are retained in the chapter list even when unauthenticated; if opened without logging in, they display:
  `"Content restricted: mature, premium, or requires login. Open in WebView to authenticate."`.

---

## 2. Tested Galleries & Counts

| Gallery Name | DA Reported Total | Crawled Count | Emitted Chapters | Mismatch Reason |
|--------------|-------------------|---------------|------------------|-----------------|
| Small Multi-Folder (`Yuumei` Folder) | 48 | 48 | 48 | None (100% complete) |
| Medium Artist (`rossdraws`) | 535 | 535 | 535 | None (100% complete) |
| Massive Artist (`MrJay1972`) | 26,842 | ~10,000 (RSS offset limit) | ~10,000 | RSS unauthenticated ceiling (~10k items) |

---

## 3. Reader Display & Device Settings

Mihon reader settings can affect how images appear on device:
- **Crop borders:** May cut borders around artwork.
- **Scale type:** Using "Fit screen" or "Smart fit" preserves complete uncropped artwork display.
- **Split tall images:** Setting this option will split webcomics or long strips into multiple pages.
