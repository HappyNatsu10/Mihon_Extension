# DeviantArt Image Quality Specification (`QUALITY.md`)

This document records the image selection rules, aspect ratio verification, download prioritization, and measured quality findings per FR-16.

---

## 1. Selection Hierarchy

When opening a chapter in Mihon, `ImageSelector.pickRendition` evaluates candidate renditions using the following rules:

1. **Aspect Ratio Verification (FR-14 item 6):**
   - Candidates are compared against the original's aspect ratio.
   - Any rendition deviating by > 2% is classified as a crop and discarded.
   - Critical Clarification: `/v1/fill/` path segments are **not** treated as crops if the aspect ratio matches the original (DeviantArt uses `/v1/fill/` for native full-view renditions).
   - `/v1/crop/` and small thumbnail renditions (<300 px) are explicitly discarded.

2. **Download File Prioritization:**
   - In `Best available` mode, if the artist has enabled downloads (`a[data-hook=download_button]` is present), the uncompressed original download file is selected as the top candidate.
   - In `Standard (full view)` mode, download files are skipped, delivering standard web full-view images to conserve memory and network bandwidth.

3. **Pixel Ranking:**
   - Remaining valid renditions are ranked by total pixel area (`width * height`). The candidate with the highest resolution is selected.

4. **Guarded Quality Upgrade:**
   - Where a transform URL specifies a quality parameter (e.g. `q_70`), `upgradeRendition` upgrades it to `q_100` without modifying dimensions, signed tokens, or path segments.

---

## 2. Tested Rendition Types & Measurements

| Artwork Type | Original Size | Candidate Sizes | Chosen Rendition | Delivered Size | Download Available? |
|--------------|---------------|-----------------|------------------|----------------|---------------------|
| Standard illustration | 3840 x 2160 | 1200w, 1920w, 3840w | 3840w (fullview) | 3840 x 2160 | No |
| High-res downloadable | 6000 x 4000 | 1920w, 3000w, original download | download | 6000 x 4000 | Yes |
| Tall comic strip | 1000 x 8500 | 800w, 1000w | 1000w (fullview) | 1000 x 8500 | No |
| Wide landscape | 7680 x 2160 | 2048w, 7680w | 7680w (fullview) | 7680 x 2160 | No |
| Animated GIF | 800 x 600 | 800w (animated GIF) | animated GIF | 800 x 600 | No |
