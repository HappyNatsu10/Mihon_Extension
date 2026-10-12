# DeviantArt API Findings (Phase 0)

**Date:** 2026-10-11
**Investigator:** Antigravity Agent

---

## Chosen Mode: **Mode C (RSS) as primary, with HTML scraping for total count and profile details**

### Decision Rationale

| Mode | Pros | Cons | Verdict |
|------|------|------|---------|
| **A: NAPI (`_napi`)** | Fast JSON, rich data | Returns 404 without session cookies/CSRF; requires complex cookie bootstrap; may break with any site update | ❌ Not viable for unauthenticated |
| **B: Official OAuth2 API** | Well-documented, stable | Requires user-supplied client_id/secret; client_credentials only gives public non-mature content; extra setup burden | ⏸ Optional fallback (v2) |
| **C: RSS** | No auth needed, stable, documented, 60 items/page, `media:content` exposes image URLs, `pubDate` available | **Offset ceiling at ~10,000 items** (returns empty pages beyond that); gets rate-limited by CloudFront | ✅ Primary for chapter content |
| **HTML scraping** | Gallery page embeds JSON-LD with `numberOfItems`, profile details available via DOM parsing | Heavier pages, may change with redesigns | ✅ Complement to RSS for total count + profile |

**Key insight:** The existing DeviantArt extension in the keiyoushi repo already uses RSS + HTML scraping. Our enhanced version extends this with:
1. **Batched synthetic chapters** (not one-chapter-per-artwork)
2. **Link-in-search resolution** (paste any DA URL)
3. **Total count from JSON-LD** (`numberOfItems` in `schema.org` structured data)
4. **Preferences** (batch size)

---

## [VERIFY] Item Resolution

### NAPI Endpoints
- **Status: NOT VIABLE** — `_napi` endpoints (e.g., `/_napi/da-user-profile/api/gallery/contents`) return **HTTP 404** without proper session cookies and CSRF tokens. These are internal endpoints for the React SPA frontend and require a full browser session context.

### oEmbed Endpoint
- **Status: PARTIALLY WORKING** — `https://backend.deviantart.com/oembed?url=<encoded_url>` works but many artwork URLs return `404 Not Found - Deviation not found` even for seemingly valid artworks. This endpoint appears unreliable for arbitrary artwork URLs.
- **Response format:** Plain text error or JSON with `author_name`, `title`, `url`, `thumbnail_url`, etc.
- **Decision:** For artwork URLs like `/{user}/art/{slug}-{id}`, the username is already in path segment 1, so **no oEmbed call is needed**. For `fav.me` and `/deviation/{id}` URLs, we'll attempt oEmbed but fall back gracefully.

### RSS Endpoint
- **URL:** `https://backend.deviantart.com/rss.xml?type=deviation&q=gallery:{username}&offset={offset}`
- **Page size:** 60 items per page (fixed, no `limit` parameter)
- **Pagination:** `<atom:link rel="next" href="...&offset=60"/>` — follow `next` links
- **Offset ceiling:** **~10,000 items maximum** — beyond this, pages return empty or 403 CloudFront errors
- **Rate limiting:** CloudFront blocks after rapid sequential requests (403 errors). Need ≥500ms between requests.
- **Fields per `<item>`:**
  - `<title>` — deviation title
  - `<link>` — full URL to deviation page
  - `<pubDate>` — RFC 2822 date (e.g., `Sat, 01 Jan 2026 12:00:00 GMT`)
  - `<media:credit>` — artist username
  - `<media:content>` — image URL with dimensions
  - Thumbnail/preview URLs in `<media:thumbnail>` and inline HTML

### Gallery HTML Page
- **URL:** `https://www.deviantart.com/{username}/gallery` or `?page=N`
- **Embedded JSON-LD:** Contains `schema.org` `CollectionPage` with:
  ```json
  {
    "@type": "Collection",
    "numberOfItems": 535,
    "creator": {"name": "rossdraws"}
  }
  ```
- **Meta description:** Contains item count: `"535 works of art across all galleries"`
- **Profile details:** Available via DOM selectors (existing extension pattern)
- **Decision:** Use JSON-LD `numberOfItems` for total count `T` — **single request**, no probing needed!

### Image URLs
- **Format:** Wixmp CDN URLs with JWT tokens in `?token=` parameter
- **Token structure:** `eyJ...` JWT, contains path constraints and audience
- **Token expiry:** JWTs have implicit expiry; should be resolved fresh each time
- **Full-size pattern:** Remove `/v1/...` path segment before `?token=` to get original (existing extension uses this pattern)
- **Example:** `https://images-wixmp-ed30a86b8c4ca887773594c2.wixmp.com/f/{uuid}/{devid}.jpg?token=eyJ...`

### Artwork Page Structure
- **Single image:** `<img fetchpriority="high">` contains the full-view image
- **Multi-page (lit/comics):** `[draggable=false]` container with child buttons, each containing `<img>` with thumbnails that can be converted to full-size by removing `/v1/...` before `?token=`
- **Existing extension already handles this** in its `getPageList` method

---

## Test Accounts

| Username | Deviations | Use Case |
|----------|-----------|----------|
| MrJay1972 | ~26,800 | Large gallery (20k+ test) |
| rossdraws | ~535 | Medium gallery |
| (any new account) | 0 | Empty gallery test |
| nonexistent_user_xyz | N/A | 404 test |

---

## Rate Limiting Observations

- **RSS:** CloudFront returns 403 after ~10-15 rapid requests. Need at minimum 500ms between requests.
- **HTML pages:** Standard CloudFront protection; the existing extension uses `network.client` which includes CloudflareInterceptor.
- **Recommendation:** Use `rateLimit(permits = 2, period = 1.seconds)` for the backend RSS host, and the default CloudflareInterceptor for HTML pages.

---

## Architecture Decision

Given these findings, the extension will:

1. **Use RSS for chapter content** — fetch deviation lists via RSS, limited to what RSS can reach
2. **Use HTML scraping (JSON-LD) for total count** — single request to gallery page gets `numberOfItems`
3. **Use HTML scraping for profile details** — follow existing extension pattern
4. **Keep the existing extension's page-list approach** — scrape individual deviation pages for full-size images
5. **Add batched synthetic chapters** — group RSS items into configurable batches (24/48/96/192)
6. **Add link-in-search** — resolve pasted DA URLs to artist profiles

### Limitation Acknowledgment
For galleries >10,000 items, only the most recent ~10,000 deviations will be accessible via RSS. This is documented to the user. The official API (Mode B) would be needed for full access to larger galleries, but requires user-provided credentials.
