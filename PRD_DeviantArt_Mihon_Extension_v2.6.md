# PRD: DeviantArt Source Extension for Mihon (v2.6)

**Doc version:** 2.6 (supersedes v1.0 to v2.5)  |  **Target builder:** Google Antigravity  |  **Language:** Kotlin  |  **Output:** Installable Mihon extension APK

## Changes from v1.0 (read first)

1. **Artworks are NOT merged into batches.** Every artwork is its **own chapter**, named after the artwork, in the same order as the artist's gallery.
2. **A library entry = one gallery**, not one artist:
   - Pasting an **artwork link** (or profile link) → the artist's **"All" gallery**.
   - Pasting a **gallery (folder) link** → **that specific gallery folder**.
3. Because a chapter list must be returned in full, the 20k+ requirement is now solved with a **crawl + on-disk cache + incremental refresh** design (Section 5), not batching.
4. Batching is kept only as an optional fallback mode (Appendix A).
5. **Browse now offers Popular, Latest, and "Latest from followed Deviants"** (FR-7). Mihon's Browse screen natively has only **Popular**, **Latest** and a **Filter** sheet, so the followed feed is exposed as a **Filter option** (plus a `feed:followed` search shortcut). It requires the user to be logged in to DeviantArt through Mihon's WebView.
6. Phase 0 is split into **0a (anonymous)** and **0b (logged-in, user-assisted)**.
7. **403 hardening (new FR-11 + Section 11a).** Testing showed some chapters failing with a bare "Error: 403" even though the artwork is public, and some galleries failing to list chapters. Every failure must now be classified with a specific cause, retried where sensible, and image URLs must be used exactly as the API signs them.
8. **Library compatibility (new FR-12 + Section 11b).** Galleries already in the Mihon library must keep updating after the extension changes: stable source identity, legacy URL normalization, stable chapter URLs, a versioned persistent cache, a resumable time-budgeted crawl, and explicit errors instead of silent empty lists.
9. **Read-history preservation (FR-13) and complete, uncropped galleries (FR-14), Section 11c.** An update must never reset the read history of an existing library entry, every artwork in the gallery must appear as a chapter (nothing silently dropped, so `skip_non_images` is removed), and each artwork must be shown whole (not a cropped rendition).
10. **Folder links fixed (new FR-15 + Section 11d).** Pasting a gallery-folder link did not produce that folder. The link reader is now tolerant (no `https://`, a link inside pasted text, mixed-case paths, `m.` and subdomain hosts), one `entryUrl()` / `normalizeEntryUrl()` pair is used by every code path, cache and ledger file names always include the folder id, folder entries call the folder-specific API parameter, and a failed folder-name lookup no longer fails the entry. The Section 7 skeleton was rewritten.
11. **Chapter ledger (FR-13 items 7 to 10).** A permanent record of every chapter ever returned (id to first-seen path, title, date) that survives cache rebuilds, `clear_cache`, schema upgrades, logout/login and mature-content changes. `getChapterList` may never return a list that omits anything in the ledger. This closes the remaining ways an update can wipe read history.
12. **Cropping made concrete (FR-14 items 6 and 8).** A rendition is a crop only if its aspect ratio differs from the original's; a `/v1/fill/` path segment alone is **not** a crop signal (DeviantArt's normal full-view URLs use it). The README documents the Mihon reader settings that can crop or split images on the device. *(v2.5 wrongly rejected every `/v1/fill/` URL; corrected in v2.6.)*
13. **Full-quality images (new FR-16 + Section 11e).** Artworks were displayed below full quality. The extension must now choose the rendition with the most pixels (not the first or the "fullview" label), prefer the real original when the artist allows downloads, test a guarded quality-parameter upgrade, add an `image_quality` preference, and write a measured `docs/QUALITY.md`. Where DeviantArt itself caps a non-downloadable artwork's public size, the extension shows the best public version and says so; it never bypasses download protection.

---

## 0. How to use this document with Antigravity

1. Fork/clone **keiyoushi/extensions-source** and build inside it.
2. Save this file in the repo root as `PRD.md` (replace v1.0).
3. Copy **Section 14 (Agent Rules)** into the Antigravity workspace rules.
4. Paste the **Kickoff Prompt (Section 15)** into the Agent Manager.
5. Work **phase by phase (Section 11)**. Phase 0 (API discovery) is mandatory before any extension code.

> Anything marked **[VERIFY]** is an unconfirmed assumption. The agent must confirm it in Phase 0 (browser network inspection + `curl`) and record the result in `docs/API_FINDINGS.md`. If reality differs from this document, reality wins.

---

## 1. Overview

### 1.1 Problem
Mihon reads "entries" made of "chapters" of image pages. DeviantArt (DA) has no Mihon source. The user wants to follow DA artists' galleries inside Mihon by pasting a link.

### 1.2 Product goal
A Mihon extension named **DeviantArt** where:
- Pasting an **artwork link** into Browse → Search shows that artist's **All gallery** as an addable entry.
- Pasting a **gallery link** (a specific folder) shows **that gallery**.
- Opening the entry lists **every artwork as its own chapter** (artwork title, real publish date), matching the order on DA. Opening a chapter shows the artwork image(s).
- It works for galleries with **20,000+ artworks**.
- The Browse screen shows **Popular**, **Latest**, and **Latest from followed Deviants** (the last needs a DA login via Mihon's WebView).

### 1.3 Non-goals (v1)
- Logging in inside the extension / storing DA credentials.
- Favourites, Scraps, Journals, Literature, comments, watchlist, keyword/tag search.
- Bypassing paywalls, premium/download protection, or age gates.
- Publishing to Google Play.

---

## 2. User stories

| # | Story | Priority |
|---|-------|----------|
| U1 | I paste `https://www.deviantart.com/<artist>/art/<title>-<id>` into Search and get that artist's **All gallery** entry. | P0 |
| U2 | I paste a profile link or `/<artist>/gallery` and get the **All gallery** entry. | P0 |
| U3 | I paste `https://www.deviantart.com/<artist>/gallery/<folderId>/<slug>` and get **that folder** as its own entry. | P0 |
| U3b | I paste a folder link in any common form (no `https://`, mixed case, copied from a share sheet with extra text, `{user}.deviantart.com` host) and still get that folder. | P0 |
| U4 | I open an entry and see each artwork as a separate chapter named by title, newest first, like the gallery. | P0 |
| U5 | I add a 20k+ gallery to my library; the first load completes (one-time), later refreshes are fast. | P0 |
| U6 | New uploads appear as new chapters on library update. | P0 |
| U6b | Updating a gallery never resets my read history, and clearing the cache does not lose it. | P0 |
| U7 | I can clear the extension's gallery cache from settings. | P1 |
| U8 | I open the source in Browse and see **Popular** artworks; tapping one opens that artist's All gallery. | P1 |
| U9 | I open **Latest** and see the newest artworks site-wide. | P1 |
| U10 | After logging in via WebView, I can see **Latest from followed Deviants** (Filter → Feed, or search `feed:followed`). | P1 |
| U11 | If I'm logged out, the followed feed tells me clearly to log in via WebView. | P1 |
| U12 | Tapping deviantart.com links elsewhere can open them in Mihon. | P3 (stretch) |

---

## 3. Concept mapping (DeviantArt → Mihon)

| DeviantArt | Mihon | Notes |
|------------|-------|-------|
| A gallery: **All** or a **folder** of an artist | `SManga` | `url = "/{user}/gallery/all"` or `"/{user}/gallery/{folderId}"` |
| Artist avatar | `thumbnail_url` | |
| Artist bio + counts / folder name | `description` | |
| **One artwork (deviation)** | **`SChapter`** | `name = title`, `url = "/{user}/art/{slug}-{id}"`, `date_upload = publish time` |
| Image(s) of that artwork | `Page` | Usually 1 page per chapter |

**Entry titles:** All gallery → `"{username}"`. Folder → `"{username} – {folderName}"`. (Keeps entries for the same artist distinguishable in the library.)

---

## 4. Functional requirements

### FR-1 Source definition
- `HttpSource` + `ConfigurableSource`, `lang = "all"`, `name = "DeviantArt"`, `baseUrl = "https://www.deviantart.com"`, `supportsLatest = true`.
- Package `eu.kanade.tachiyomi.extension.all.deviantart`, module `src/all/deviantart/`.

### FR-2 Link-in-Search resolution (core feature)
Override the search entry point (`fetchSearchManga` or `getSearchManga`, matching the template's extensions-lib version). Before any normal search, try to interpret `query` into a **`GalleryRef(user, folderId?)`** (`folderId == null` means **All gallery**).

| Input | Result |
|-------|--------|
| `https://www.deviantart.com/{user}/art/{slug}-{id}` (**artwork link**) | **All gallery** of `{user}`, no network call needed to resolve |
| `https://www.deviantart.com/{user}` | All gallery |
| `https://www.deviantart.com/{user}/gallery` or `/gallery/all` | All gallery |
| `https://www.deviantart.com/{user}/gallery/{folderId}` or `/gallery/{folderId}/{slug}` (numeric id) | **That folder** |
| Any of the above **without `https://`** (`deviantart.com/{user}/gallery/{folderId}`), with a `m.` or `www.` host, with mixed-case path words (`/Gallery/`), a trailing slash, a query string or a `#fragment` | Same result as the full form |
| Pasted text that contains a link (for example `Check this out https://www.deviantart.com/...` from a share sheet) | The first DeviantArt or `fav.me` link in the text is used |
| `https://{user}.deviantart.com/...` (legacy subdomain, `user != www`) | Same rules applied to the path |
| `https://www.deviantart.com/deviation/{id}`, `https://www.deviantart.com/art/{slug}-{id}` (no user in path), `https://fav.me/d{base36}` | oEmbed lookup → `author_name` → **All gallery** (`https://backend.deviantart.com/oembed?url={urlEncoded}`) **[VERIFY]** |
| `user:{name}` or `@{name}` | All gallery |
| Bare text matching the username regex | Validate as a username → All gallery |

Rules:
- Reserved first path segments are **not** usernames: `tag, search, topic, topics, watch, shop, join, about, developers, checkout, settings, messages, notifications, daily-deviations, popular, newest, core-membership, studio, users, today, deviation, art, journal`. `art` and `deviation` go to the oEmbed path.
- Username regex: `^[A-Za-z0-9][A-Za-z0-9-]{1,}$` (case-insensitive; store the canonical casing returned by DA).
- Only `gallery/{numeric id}` selects a folder. Anything else after the username (including `gallery/scraps`, `/favourites`) resolves to **All gallery** in v1. Path words are matched case-insensitively and parsing follows FR-15 (the Section 7 skeleton).
- Output: a `MangasPage` with **exactly one** `SManga`, `hasNextPage = false`, populated enough to add from the search grid.
- Unresolvable input → empty result (never crash).

### FR-3 Entry validation + details (`getMangaDetails`)
- Validate the user (and folder, if any) exists.
- All gallery: `title = username`, `author = artist = username`, `thumbnail_url = avatar`, `description = bio/tagline + "Gallery: ~N artworks"` (N only if cheaply known), `status = ONGOING`, `genre = ["Gallery"]`, `initialized = true`.
- Folder: same, plus `title = "{username} – {folderName}"` and folder name in the description. Folder name/metadata comes from the folder listing endpoint **[VERIFY]**. Also **[VERIFY]** how the numeric folder id in the website URL maps to the API's folder id (official API uses UUID-style ids; website/napi URLs use numeric ids).
- **A failed folder-name lookup never fails the entry** (FR-15 item 4): use the title `"{username} – Folder {id}"` and still list the chapters.
- 404 / deactivated / deleted folder → `Exception("Gallery not found or unavailable")`.

### FR-4 Chapter list (`getChapterList`): **one chapter per artwork**
- Source of truth is the **gallery cache** (Section 5). The function returns the **complete** list every time, because Mihon deletes chapters missing from the returned list.
- For each cached artwork:
  - `name = title` (fallback `"Untitled"`; trim/sanitise whitespace).
  - `url = "/{user}/art/{slug}-{id}"` (the artwork's real DA path; fallback `/deviation/{id}`). This makes "Open in browser" work.
  - `date_upload = published time in epoch ms` **[VERIFY field]**.
  - `chapter_number = position counted from the oldest (1-based)`. It stays stable when new art is added at the top.
  - `scanlator = null`.
- **Order returned:** newest first, exactly the DA gallery order.
- **Nothing is filtered out of the chapter list** (FR-14): every item the gallery listing returns becomes a chapter. Artworks that cannot be shown as images (text/literature, journals, videos, mature without login, premium) still appear and fail only when opened, with a specific message.
- Empty gallery → empty list (not an error).

### FR-5 Page list (`getPageList`)
- Extract the deviation id (and user) from the chapter URL (trailing `-(\d+)` of the last segment, or `/deviation/(\d+)`).
- Fetch **fresh** data for that single artwork. **Do not** reuse image URLs from the cache, because they carry expiring tokens. Candidate endpoints **[VERIFY]**: an internal "extended fetch" JSON for one deviation (e.g. `/_napi/shared_api/deviation/extended_fetch?deviationid={id}&username={user}&type=art`), the official `GET /api/v1/oauth2/deviation/{id}`, or oEmbed as a last resort.
- Pages = the artwork's full-size image(s) (support multi-image deviations if they exist **[VERIFY]**). Set `Page.imageUrl` directly; `url` keeps the artwork path.
- No usable image (mature-gated, premium, non-image) → `Exception("This artwork is not available without login")` or a clear equivalent.
- `imageUrlParse` → `throw UnsupportedOperationException()` if `imageUrl` is always pre-filled.

### FR-6 Image requests
- `imageRequest` adds `Referer: https://www.deviantart.com/` and a realistic `User-Agent`.
- Choose the rendition by the FR-16 selection rules (most pixels, aspect ratio intact, original/download file first when allowed). Animated GIFs: use the animated original/GIF rendition if exposed **[VERIFY]**.
- **Use image URLs exactly as the API provides them (the only exception is the guarded `upgradeRendition` in FR-16 item 3)**, including the signed `token` query parameter. Do not edit width/height/quality path segments, strip the token, or rebuild the URL by hand: signed tokens usually authorise only specific paths/sizes, and a modified URL returns **403** **[VERIFY]**.
- **Fallback chain on an image 403:** (1) re-fetch the single artwork fresh (new token) and retry the same rendition once; (2) try the next-best rendition (`fullview` → `preview` → others), each with the token that came with it; (3) fail with a classified message (FR-11). Never reuse image URLs from the cache or from an earlier session.
- **Show the whole artwork, never a crop:** see FR-14 item 6. A page image must keep the original aspect ratio.

### FR-7 Browse: Popular, Latest, Latest from followed Deviants

Mihon's Browse screen natively provides **Popular**, **Latest** and a **Filter** sheet (there is no way to add a third tab). Mapping:

| Mihon UI | Content | Login |
|----------|---------|-------|
| **Popular** tab | Popular artworks on DeviantArt **[VERIFY exact feed/time range]** | No |
| **Latest** tab | Newest artworks site-wide | No |
| **Filter → Feed → "Latest from followed Deviants"** (also the search text `feed:followed`) | Newest artworks from the deviants the user follows (DA's Watch feed) | **Yes** |

Rules:
- **Entry model:** each feed item is an artwork, but the tile is that **artist's All gallery** entry (`url = "/{user}/gallery/all"`, title = username). The tile cover is the feed artwork's thumbnail (so the grid looks like DA's feed); `getMangaDetails` later replaces the cover with the avatar. Tapping a tile is equivalent to pasting an artwork link (FR-2).
- **De-duplicate authors** within a page (first occurrence wins). Mihon de-duplicates across pages by URL.
- **Paging:** offset-based. `offset = (page - 1) * limit`; `hasNextPage` from the API (`hasMore`/`nextOffset`) **[VERIFY]**.
- **Non-images:** feed tiles represent the artist's gallery, so keep them; drop only items with no usable author. If a page yields no tiles but more pages exist, fetch the next page (max 3 attempts) so scrolling does not stop.
- **Search precedence** (`getSearchManga`): (1) the query resolves to a link/username → gallery entry (FR-2); (2) else if the query is `feed:followed` **or** the Feed filter is "Latest from followed Deviants" → followed feed; (3) else empty result.
- **Filters** (`getFilterList`): `Filter.Header("Paste a DeviantArt link in the search bar to open a gallery")` and `Filter.Select("Feed", ["None", "Latest from followed Deviants"])`.
- **Login for the followed feed:**
  - Mihon's HTTP client shares cookies with the app's WebView. The user logs in via **Browse → DeviantArt → WebView (globe) icon**, signs in, then returns. Social logins (Google/Facebook) may be blocked inside WebViews, so recommend DA username + password **[VERIFY]**.
  - Detect the logged-out state **before or on** calling the feed: missing/invalid DA session cookies in the shared cookie jar **[VERIFY cookie names]** or the endpoint's auth-required response (401/redirect/JSON error **[VERIFY]**).
  - Logged out or session expired → throw `Exception("Log in to DeviantArt: open this source in WebView (globe icon), sign in, then try again.")`.
  - The extension must **never** ask for, store, or transmit credentials, and must never log cookie or token values.
  - Following nobody / empty feed → empty page (not an error).
- **Mature content:** feeds show only what the logged-in account's settings allow; anonymous feeds are non-mature only.

### FR-8 Preferences (`ConfigurableSource`)
| Key | Type | Default | Purpose |
|-----|------|---------|---------|
| `clear_cache` | Preference (click action) | n/a | Deletes the cached crawl data (forces a full re-crawl). **Never deletes the chapter ledger** (FR-13 item 7) |
| `chapter_mode` | List: "Each artwork" / "Batches" | Each artwork | Batches = Appendix A (performance fallback; P2) |
| `image_quality` | List: "Best available" / "Standard (full view)" | Best available | FR-16: "Standard" skips the original/download file and any quality upgrade (smaller, lighter on memory and data). Changes only which image is shown, never which chapters are listed |
| `debug_log` | Switch | false | Redacted request/response diagnostics (FR-11) |
| `api_client_id` / `api_client_secret` | Text | empty | Optional official-API mode (Section 6, Mode B) |

### FR-9 Mature content
- Unauthenticated requests return only non-mature items. Mihon's HTTP client shares cookies with the app WebView, so a user who logs in via **Open in WebView** gets mature content and the **followed feed** (FR-7) automatically. The extension must **never** ask for, store, or transmit DA credentials. Gated items stay in the chapter list and fail only when opened, with a clear message; they never crash.

### FR-10 (Stretch) Deep link
- Intent filter for `deviantart.com` + `UrlActivity` forwarding to Mihon's `eu.kanade.tachiyomi.SEARCH` intent with the URL as `query` (follow existing extensions in the template repo).

### FR-11 Request hardening and 403 handling (bug-fix requirement)
Observed in testing: (a) some chapters fail with a bare **"Error: 403"** although the artwork is public, and (b) some galleries fail to list their chapters. Requirements:

1. **Classify every non-2xx response in one place** (an `HttpGuard` interceptor/helper used by all API and image requests). The user-facing message must include the status, the cause and a short endpoint label (`gallery list`, `artwork fetch`, `image`, `feed`), **never** the full signed URL, token or cookie. A bare "Error: 403" must never reach the user.

| Cause | Detection **[VERIFY each]** | Behaviour / message |
|-------|-----------------------------|---------------------|
| Cloudflare / anti-bot check | HTML body, `cf-mitigated: challenge` header, "Just a moment" | "DeviantArt asked for a browser check. Open the source in WebView (globe icon), let it load, then retry." |
| Rate limiting shown as 403 or 429 | 429, `Retry-After`, or 403 shortly after a burst | Adaptive slow-down + backoff + retry (item 3) |
| Expired / invalid / altered image token | 403 from the image CDN host | Refetch the artwork fresh and retry; then fallback rendition (FR-6) |
| Mature / age-gated item or gallery | JSON flag (e.g. `isMature`) or login-required body | "This is mature-restricted. Log in via WebView and enable mature content in your DeviantArt settings." |
| Private / restricted folder or group gallery | JSON error code | "This gallery is private or restricted." |
| Wrong folder id mapping | 403/404 only for folder entries | Re-resolve the folder id (numeric URL id vs API id) and retry once |
| Missing CSRF / bootstrap cookie / headers | 403 JSON on API calls | Run the bootstrap request (profile page) once, retry |
| Stale or invalid session cookies | 403 only while cookies are present; works after logging out/in | "Your DeviantArt session looks invalid. Log out and back in via WebView." |
| Unknown | anything else | "DeviantArt returned 403 (unknown cause) at <endpoint label>"; details to debug log |

2. **Retries:** retry 403/429/5xx up to **3 times** with exponential backoff and jitter before surfacing an error. In a gallery crawl, a failing page is retried, never skipped; on final failure throw the classified error and keep the partial cache so the crawl resumes later.
3. **Adaptive rate limit:** start at ~2 requests/s for DA API hosts; on any rate-limit-like response halve the rate (minimum 0.5 req/s) for 60 s. The image CDN host has its own, separate limiter. Update the numbers with the Phase 0 measurement.
4. **Consistent headers on every request:** the same `User-Agent` Mihon uses for its WebView, plus `Referer: https://www.deviantart.com/`, `Accept`, `Accept-Language`. API JSON calls also send `X-Requested-With: XMLHttpRequest` and a CSRF token if the endpoint needs one **[VERIFY]**.
5. **`debug_log` preference** (Switch, default off): when on, log method, endpoint label, status, classified cause, response header **names** (not values) and a ≤200-char body snippet with tokens and cookies redacted.
6. **Known failing cases:** keep `docs/403_CASES.md` listing every artwork/gallery that returned 403, its root cause, and the fix. These become regression fixtures.

### FR-12 Library compatibility: galleries already in the library must keep updating
Problem: galleries added to the Mihon library by earlier builds do not update after the extension changed. Mihon stores the `SManga.url`, the chapter URLs and the source id at add time and passes them back on every refresh. **The extension cannot rewrite stored URLs, so every build must stay compatible with what earlier builds stored.**

1. **Stable source identity.** Never change `extName` ("DeviantArt"), `lang` ("all"), the source `versionId` or the package `eu.kanade.tachiyomi.extension.all.deviantart`. Mihon derives the source id from these; changing them orphans library entries ("source not installed"). Always sign builds with the **same keystore** (otherwise Android refuses to update in place) and **bump `extVersionCode` on every build** that is installed over an older one.
2. **Accept every legacy entry URL.** One function `normalizeEntryUrl(url: String): GalleryRef?` is used by `getMangaDetails`, `getChapterList`, `getMangaUrl` **and link-in-search (FR-15)**. It must accept (any casing, with or without the domain, trailing slash, query string): `/{user}`, `/{user}/gallery`, `/{user}/gallery/all`, `/{user}/gallery/{numericFolderId}[/{slug}]`, and **every other shape any earlier build stored**. The agent must find those shapes in the git history and current code and list them in `docs/COMPAT.md`.
3. **Never fail silently.** If an entry URL cannot be parsed, throw `Exception("Unrecognised library entry URL: <path>. Open the entry and use Migrate, or add it again.")`. Never return an empty chapter list for a valid library entry.
4. **Stable chapter URLs.** The chapter URL format must never change between builds, and one list must never mix formats. `docs/COMPAT.md` records the format of every released build. If earlier builds stored a different format than FR-4 specifies, **keep the format existing library entries already contain** (ask the user before changing it). The cache stores the **first-seen path** per artwork id and re-emits it unchanged, so a later title/slug edit on DeviantArt never creates a duplicate chapter.
5. **`getPageList` accepts all legacy chapter URL formats:** `/{user}/art/{slug}-{id}`, `/{user}/art/{id}`, `/art/{slug}-{id}`, `/deviation/{id}`, and any older shape from earlier builds. For batch-style URLs from an earlier design, either serve them through the Appendix A logic or throw `Exception("This chapter came from an older version of the extension. Pull down to refresh the entry, or use Migrate.")`.
6. **Versioned persistent cache.** Each cache file has `schemaVersion`; a mismatch or corruption means discard and rebuild (resumable). Store caches under `filesDir/deviantart/`, **not** `cacheDir` (Android can purge cache directories, forcing a surprise full re-crawl in the middle of a library update).
7. **Resumable, time-budgeted crawl.** A single `getChapterList` call may crawl for at most ~4 minutes (a named constant), saves progress, and if still incomplete throws `Exception("Loading gallery: 12,400 artworks cached so far. Update again to continue.")`. Repeated library updates continue until complete. **Never return a partial list:** Mihon removes chapters missing from the returned list, which would delete read progress and downloads.
8. **Update detection.** Keep `SManga.status = ONGOING` (Mihon skips COMPLETED entries in library updates) and never set `update_strategy` to fetch-only-once. Incremental refresh follows Section 5.3. If the cache is complete but the first API page contains no known id, keep paging until a known id is reached or the crawl budget runs out (the artist may have posted many artworks since the last refresh).
9. **Concurrency:** Mihon refreshes several entries of one source at once; use the per-gallery mutex and the shared rate limiter so parallel updates do not trigger 403/429.
10. **Diagnostics:** with `debug_log` on, log the stored entry URL (path only), the detected chapter-URL format counts, cache state (`schemaVersion`, `complete`, item count) and the outcome of each refresh.
11. **Migration note for the README:** Mihon's **Migrate** works with this extension because `chapter_number` is the position from the oldest artwork and the entry title is the username, which the search box resolves (FR-2).

### FR-13 Read-history preservation (an update must never reset an entry)
Problem: after updating a gallery that is already in the library, the read history is lost and the entry behaves like a new one. How Mihon works: it identifies chapters by `SChapter.url`. Chapters in its database that are **missing** from the list returned by the extension are deleted, together with their read state, last page read, bookmarks, downloads and history entries. New URLs are inserted as brand-new unread chapters. As a safety net only, Mihon re-marks a re-added chapter as read when its `chapter_number` is positive and equals the number of a deleted chapter that was read.

Requirements:
1. **Chapter URLs are byte-for-byte stable** across builds, refreshes, cache rebuilds, schema upgrades, preference changes, login state and mature-content visibility. They must not depend on the title or slug (use the cache's first-seen path, FR-12 item 4), the domain, query parameters, letter case or a trailing slash.
2. **Existing library data wins.** The chapter-URL format emitted today must be exactly the one stored in the user's library. To find out what is stored, use (in this order): git history of the chapter-URL code; then a **Mihon backup file (.tachibk, a gzip-compressed protobuf)** that the user provides locally, from which only this source's entry URLs, chapter URLs and read flags are extracted (never commit the backup; sanitize any fixtures). Record the result in `docs/COMPAT.md`.
3. **Never remove a chapter that was previously listed.** No partial lists, no filters that can hide previously listed artworks, no preference that changes which artworks are listed (this is why `skip_non_images` is removed).
4. **`chapter_number` is a positive, unique, stable value** (1-based position from the oldest artwork). Never 0 or negative.
5. **Mihon-sync simulator test.** Implement a small test helper that mimics Mihon's sync rule (match by URL; removed = in database but not in the new list; new = in the new list but not in the database; re-added chapters inherit read state by `chapter_number`). Run it with the old chapter URLs from the user's backup/fixtures against the list the current build produces. **Acceptance: zero removed chapters and zero read chapters lost.**
6. **Entry identity:** `SManga.url` of existing entries is never changed or "migrated" by the extension (FR-12). Updating must never create a second library record for the same gallery.
7. **Chapter ledger (permanent).** Next to the crawl cache keep `filesDir/deviantart/ledger_{userLowercase}_{folderId|all}.json` containing `{ "schemaVersion": Int, "entries": { "<deviationId>": { "path": "...", "title": "...", "publishedMs": 0 } } }`. It is appended to every time a chapter list is returned. It is **never** deleted or rewritten by `clear_cache`, a crawl-cache rebuild or schema upgrade, logout/login, or a change in mature-content visibility. When the crawl cache is rebuilt, the chapter URL of every artwork already in the ledger is taken **from the ledger**, not from the fresh crawl, so a renamed artwork keeps its old URL.
8. **Superset invariant.** Before `getChapterList` returns, check `ledger ids ⊆ returned ids`. Ids that are in the ledger but missing from the fresh crawl (deleted, mature while logged out, premium, hidden by a changed setting) are re-emitted from the ledger with their stored path, title and date. If the invariant cannot be satisfied, throw a specific error; never return the shorter list. (This is how a logged-in crawl followed by a logged-out one avoids deleting read chapters.)
9. **Seeding the ledger.** The first time this build runs for an entry: use the ledger if it exists; otherwise seed it from a legacy crawl-cache file (any `schemaVersion`, any older file name, including a mixed-case user); otherwise the chapter-URL format must be the one recorded in `docs/COMPAT.md` (item 2) and must be proven with the simulator (item 5) before shipping. A corrupt ledger is renamed to `.bad` and rebuilt from the crawl cache, never discarded silently.
10. **Ledger diagnostics.** With `debug_log` on, log per refresh: ledger size, crawl size, returned size, ids added, ids retained from the ledger only, and any artwork whose stored path differs from the freshly seen path (a renamed artwork).

### FR-14 Complete gallery listing and uncropped images
Problem: some artworks of the gallery do not appear, and/or artworks are not shown whole. Requirements:
1. **Completeness invariant.** Number of chapters == number of items returned by the gallery listing across all pages. **No silent drops.** Items with missing optional fields are kept (`title` → "Untitled", unknown time → `0`). De-duplicate by id only.
2. **Pagination correctness.** Do not stop on `hasMore` alone: stop when a page returns zero items **and** `hasMore` is false. Handle a possible offset ceiling **[VERIFY]**, overlapping or shifting pages, and the resume overlap (Section 5.3).
3. **Cross-check the total.** After a crawl, compare the crawled count with the expected total from an independent source **[VERIFY which]**: folder `size` from the folder list, the profile gallery/deviation count, or offset probing. If crawled < expected minus a small tolerance, log it, run a **fallback crawl by folder** (list the gallery folders, crawl each, union by id), and re-compare. If it is still short, throw nothing, keep the list, and add a visible line to the entry description: `Artworks listed: X of ~Y reported by DeviantArt`.
4. **Hidden items are explained, not dropped.** Mature items (logged out), premium items and non-image items remain chapters and show their specific message when opened. The entry description includes a count of items that need a login, if known.
5. **Multi-image artworks:** if DeviantArt returns several images for one artwork **[VERIFY]**, the chapter has one page per image, in order.
6. **No cropping.** The page image must show the entire artwork:
   - Use the rendition the API provides as-is (FR-6). Never use thumbnail/preview renditions as pages, and never build a `fill`/square-crop rendition by hand.
   - Validate aspect ratio: the rendition's width/height must match the original's within ~2% **[VERIFY field names for original size]**. If not, treat it as cropped and try the next rendition or the original-path URL with its own valid token.
   - Very tall or very wide images (comic strips, banners) must not be cut by a height/width cap. Test with a very tall image, a very wide image, a square one and an animated GIF.
   - **Rendition rules [VERIFY each in Phase 0]:** a rendition is a crop **only if its aspect ratio differs from the original's by more than ~2%**. Do **not** reject URLs just because the path contains `/v1/fill/`: DeviantArt's own full-view URLs use `fill` with the original's width and height, and that is not a crop. Compare the `w_`/`h_` values in the path (or the type's width/height) with the original size. Never use `/v1/crop/`, square-thumbnail or `300W`-style types as pages. Selection among valid renditions follows FR-16. Log the rendition kind and its width/height vs the original (never the token).
   - The extension returns image bytes unmodified: no interceptor may resize, re-encode or crop a page image.
7. **Completeness report:** write `docs/COMPLETENESS.md` for 3 galleries (small, medium, 20k+) with: DeviantArt-reported count, crawled count, chapter count, items needing login, and any mismatch explanation.
8. **Cover vs pages, and device settings.** Mihon always crops entry **covers** to a portrait ratio; that is expected and not a bug. Only **reader pages** must be whole. If pages still look cut on the device after the rules above pass, the cause is Mihon's reader settings (for example crop borders, a fill-screen scale type, or splitting tall images; names vary by Mihon version). Document these in the README.

### FR-15 Folder links must open that folder (bug-fix requirement)

Observed in testing: pasting a gallery-folder link (`/{user}/gallery/{folderId}/{slug}`) did not give that folder as an entry. Most likely causes, in order: (a) the link was not recognised, because the old resolver only accepted a bare, fully qualified link with exact-case path words (it failed on a missing `https://`, text around the link, `/Gallery/`, a `m.` host); (b) the folder id was parsed but lost when the entry URL was stored or read back, so details and chapters ran as **All**; (c) the cache or ledger file name ignored the folder id, so the folder entry read the All cache; (d) the folder listing request used the wrong parameter (`all_folder=true`, or an id format the API does not accept); (e) the folder-name lookup failed and took the whole entry down with "Gallery not found"; (f) the link was a `/favourites/...` or `/gallery/scraps` link, which v1 deliberately maps to All.

Requirements:
1. **Tolerant link reading** (the Section 7 skeleton implements this). Take the first token of the query that contains `deviantart.com` or `fav.me`; strip leading and trailing punctuation; the scheme is optional; accepted hosts are `deviantart.com`, `www.`, `m.` and `{user}.deviantart.com`; path words (`gallery`, `art`) are matched case-insensitively; a query string, `#fragment` and trailing slash are ignored.
2. **One builder, one parser.** `entryUrl(ref)` is the only code that builds `SManga.url`; `normalizeEntryUrl(url)` is the only code that reads one (search, `getMangaDetails`, `getChapterList`, `getMangaUrl`, `getPageList`'s user lookup, cache and ledger file names). The user part of cache and ledger names is lower-cased and **always followed by the folder id (or `all`)**. Unit tests assert `normalizeEntryUrl(entryUrl(ref)) == ref` for All and folder refs, and that a folder ref and the All ref of the same user never share a file name.
3. **Folder-aware requests.** For `folderId != null` the gallery-contents request uses the folder parameter (`folderid={id}`) and never `all_folder=true`; for All it is the reverse. A unit test asserts the exact request built for each. **[VERIFY]** the API accepts the numeric id from the website URL; if it needs a different id, resolve it through the folder list (match on the numeric id found in the folder's own URL or id field), keep the mapping in the crawl-cache file, and record it in `docs/API_FINDINGS.md`.
4. **Folder metadata never blocks the entry.** Name and size come from the folder list. If that lookup fails or the folder is not in the list, use the title `"{user} – Folder {id}"`, log it, and still list the chapters. "Gallery not found or unavailable" is thrown only when the contents request itself reports the folder missing or deleted.
5. **Right owner.** If the folder id does not belong to the user in the link, fail with `Exception("Folder not found for this user")`. Never silently fall back to the All gallery.
6. **Counts match.** A folder entry's chapter count must equal the folder size reported by the folder list (FR-14 item 3), and its chapters must come from that folder only.
7. **Diagnostics.** With `debug_log` on, log the parsed `GalleryRef`, the entry URL (path only), which listing parameter was used, and the cache and ledger file names (never tokens or cookies).
8. **Adjacent links.** `/favourites/...`, `/gallery/scraps` and other non-numeric gallery paths resolve to All in v1; the README says so.
9. **Test table (JVM, must pass):**

| Input | Expected |
|-------|----------|
| `https://www.deviantart.com/artist/gallery/12345678/my-folder` | Gallery(artist, folder 12345678) |
| `https://www.deviantart.com/artist/gallery/12345678` and the same with a trailing `/` | Gallery(artist, folder 12345678) |
| `deviantart.com/artist/gallery/12345678/my-folder` (no scheme) | Gallery(artist, folder 12345678) |
| `www.deviantart.com/Artist/Gallery/12345678/My-Folder?page=2#top` | Gallery(Artist, folder 12345678) |
| `https://m.deviantart.com/artist/gallery/12345678/my-folder` | Gallery(artist, folder 12345678) |
| `https://artist.deviantart.com/gallery/12345678/my-folder` | Gallery(artist, folder 12345678) |
| `Look at this https://www.deviantart.com/artist/gallery/12345678/my-folder!` | Gallery(artist, folder 12345678) |
| `https://www.deviantart.com/artist/gallery`, `/gallery/all`, `/gallery/scraps`, `/favourites/555/x`, `/art/some-title-999` | Gallery(artist, All) |
| `https://www.deviantart.com/art/some-title-999`, `/deviation/999`, `https://fav.me/dabc123` | NeedsOembed (full `https://` URL) |
| `https://www.deviantart.com/tag/gallery/123` | Invalid (reserved word) |
| stored entry URL `/artist/gallery/12345678` and `/artist/gallery/all` | `normalizeEntryUrl` returns the matching ref |

---

### FR-16 Full-quality images (bug-fix requirement)

Observed in testing: artworks are displayed, but not at full quality (soft, smaller or more compressed than on DeviantArt). Likely causes, most likely first: (a) a smaller rendition is picked (the first entry of the media `types` list, a `preview`, or a fixed label) instead of the one with the most pixels; (b) the URL's transform segment limits size or quality (for example `w_`/`h_` below the original, or a low `q_` value) and nothing better is tried; (c) the gallery listing's media is reduced and only the single-artwork fetch exposes the original (`download` / original-file fields); (d) the artwork is downloadable but the download file is never used; (e) the CDN re-encodes to another format depending on the `Accept` header; (f) an interceptor or setting resizes or re-encodes the bytes; (g) an animated artwork is shown through a static rendition; (h) DeviantArt itself caps the public size of non-downloadable art (expected, not a bug; it must be documented).

Requirements:
1. **Rank by pixels, not by label.** One pure function `pickRendition(candidates, original)` in `ImageSelector.kt`:
   - candidates = every image rendition of the artwork from the single-artwork fetch **[VERIFY field names]**: the `types[]` entries with width/height, plus the original/download file when exposed;
   - discard candidates that fail the aspect-ratio check (FR-14 item 6), are thumbnails or square crops, or lack a usable token;
   - pick the candidate with the largest `width * height`; break ties by the better format (lossless or original format over re-encoded lossy);
   - never pick a candidate smaller than the best valid candidate, and never fall back to a smaller one except on a failed request (then FR-6's fallback chain applies, logged as "reduced quality").
2. **Original first, only where the artist allows it.** If the artwork is marked downloadable and the single-artwork fetch exposes the download file to the current session **[VERIFY]**, use that file as the top candidate (it is the true original). Never try to reach an original for a non-downloadable artwork, and never remove or bypass any protection (Agent Rule 11). When `image_quality = Standard`, skip this step and item 3.
3. **Guarded quality-parameter upgrade (the single exception to "never edit URLs").** `upgradeRendition(url)` may change **only** the quality value in the transform segment (for example `q_70` → `q_100`). It must not touch width or height (never upscale beyond the original), the token, or any other part of the URL. Rules: (a) Phase 0 tests it with the same token; if the CDN refuses it or returns nothing better (same or fewer bytes and same pixels), the feature is dropped and the finding recorded in `docs/API_FINDINGS.md`; (b) when kept, try the upgraded URL first and, on **any** non-2xx or a smaller body, silently retry the as-provided URL once (this is a deliberate fallback, not an FR-11 error); (c) the upgraded URL is never cached or stored (FR-5).
4. **Response check.** For every page image log (with `debug_log` on, never tokens): rendition source (`download` / `types:<name>` / `fullview`), delivered width × height, the original's width × height, the `q_` value if present, `Content-Type` and `Content-Length`. Delivered pixels below the best known valid candidate are logged as "reduced quality".
5. **Acceptance for quality.** For each of the 5 test artworks (item 6): the delivered long side is at least as large as what a logged-out desktop browser gets from DeviantArt's own "full view"; and for a downloadable artwork it is the original file's size. Where the artwork is not downloadable and DeviantArt serves a reduced public size, the delivered size equals the largest public rendition and `docs/QUALITY.md` says so.
6. **`docs/QUALITY.md`.** For 5 artworks (a small one, a very tall one, a very wide one, an animated GIF, a high-resolution downloadable one) record: original size (from the API), every candidate size, the chosen one and why, delivered size, `Content-Length`, whether the download file was available, and a note comparing with the browser's full view.
7. **Format and headers.** **[VERIFY]** whether the `Accept` header changes the encoding the CDN returns (for example forcing lossy WebP); send the `Accept` value that returns the original or the larger body. No interceptor may resize, re-encode or crop bytes (FR-14 item 6).
8. **Memory and size guard.** Constants for a maximum body size (for example 40 MB) and maximum pixel count **[VERIFY against Mihon's decoder]**. Above either, fall back to the best candidate below the limit and log "reduced quality: size guard". Prevents originals that Mihon cannot decode.
9. **Animated artwork.** Prefer the animated original or GIF rendition; confirm the frame count is more than 1 and that a static preview was not delivered **[VERIFY]**.
10. **README notes.** DeviantArt's public size limit for non-downloadable art; the `image_quality` preference; Mihon's reader and Data saver settings, which can lower quality or shrink images on the device (names and locations vary by Mihon version **[VERIFY]**).

---

## 5. Scaling design for 20,000+ artworks (one chapter per artwork)

### 5.1 Reality check
Mihon requires `getChapterList` to return **all** chapters at once, and chapter names/dates require reading every artwork's metadata. For 20,000 artworks at 24 per API page that is **~834 requests** on the first load. At a polite 2–3 requests/second that is **roughly 5–7 minutes, one time only**. After that, refreshes must cost **1–2 requests**. A 20k-chapter entry is also heavy for Mihon's UI; this is expected and accepted. (Appendix A is the escape hatch.)

### 5.2 Gallery cache (on disk)
- Location: `filesDir/deviantart/{userLowercase}_{folderId|all}.json` (lowercase the user so differently-cased links share one cache; the folder id is always part of the name), plus the permanent chapter ledger `ledger_{userLowercase}_{folderId|all}.json` (FR-13 item 7) (`context` via `Injekt.get<Application>()`, as other extensions do). Do **not** use `cacheDir` (Android can purge it) and do not use SharedPreferences for big lists.
- Content: `{ "schemaVersion": Int, "complete": Boolean, "items": [ {"id", "title", "path", "publishedMs"} ... ] }`, **newest-first**. `path` is the **first-seen** artwork path and is never rewritten (FR-12). **Never store image URLs/tokens.** `clear_cache` and corruption handling delete only this crawl file, never the ledger.
- Size target: ~100 bytes/item → ~2 MB for 20k items. Parse with streaming/`kotlinx.serialization` once per call; never hold raw API JSON longer than one page.

### 5.3 Algorithm
**First load (no cache) → full crawl**
1. `offset = 0`; loop: fetch page (≤24 items; confirm max `limit` in Phase 0), map to `CachedItem` (keep every item), append, `offset = nextOffset`, until `hasMore == false`.
2. Every ~20 pages, persist the cache with `complete = false` so an interrupted crawl can **resume**. Resume at `offset = max(0, items.size - 24)` (overlap) and de-duplicate by `id`, because uploads during the crawl shift offsets.
3. On success set `complete = true`, persist, run the ledger superset check (FR-13 item 8), update the ledger, then return all chapters.
4. On network/429/403 failure after retries, or when the crawl time budget is used up (FR-12 item 7): **throw** with a progress message (keep the partial cache). **Never return a partial list** as if complete.

**Later refreshes (cache complete) → incremental**
1. Fetch from `offset = 0` page by page **until a page contains an id already in the cache** (normally the very first page).
2. Prepend unseen items (de-dupe by id), persist, run the ledger superset check (FR-13 item 8), update the ledger, then return the full list.
3. Typical cost: **1 request**.

**Deleted artworks** remain in the cache until `clear_cache` is used (accepted limitation; document it in the README).

### 5.4 Politeness & robustness
- Rate limit the DA API host (e.g. 2–3 req/s via the template's `rateLimit`/`rateLimitHost` helper on `network.cloudflareClient`), and a separate looser limit for the image CDN host. No parallel hammering.
- HTTP **429**: honour `Retry-After`, else exponential backoff (max 3 retries), then fail with a readable error.
- Phase 0 must **measure** the real max safe request rate and update the numbers above.
- All network off the main thread. Per-gallery `Mutex` so two simultaneous refreshes of the same gallery don't both crawl.

---

## 6. Data sources & API strategy

**Mode A: Unauthenticated web endpoints (preferred default; zero setup)** **[VERIFY all]**
- Gallery contents (candidate): `https://www.deviantart.com/_napi/da-user-profile/api/gallery/contents?username={u}&offset={o}&limit=24&all_folder=true` for **All**, and the same with `folderid={numericId}` instead of `all_folder=true` for a **folder**.
  - Expected: `hasMore`, `nextOffset`, `results[]` each with a `deviation` (`deviationId`, `title`, `url`, `type`, `isJournal`, `isMature`, `publishedTime`, `media` with `baseUri`, `prettyName`, `token[]`, `types[]`).
- Folder list/names (candidate): a `gallery/folders` style napi endpoint, or the profile gallery page JSON.
- Profile/avatar (candidate): `.../da-user-profile/api/init/about?username={u}` or the profile page.
- Single deviation fetch (for page lists): see FR-5.
- If a CSRF token/cookie bootstrap is needed, add a small interceptor that loads `https://www.deviantart.com/{u}` once and caches it.

**Mode B: Official API with user-supplied credentials (optional)**
- `POST https://www.deviantart.com/oauth2/token` (`client_credentials`, user's own `client_id`/`client_secret`) → ~1 h bearer token (cache + refresh).
- `GET /api/v1/oauth2/gallery/all?username=&offset=&limit=24`, `GET /api/v1/oauth2/gallery/{folderid}?username=...`, `GET /api/v1/oauth2/gallery/folders?username=...`, `GET /api/v1/oauth2/user/profile/{u}`, `GET /api/v1/oauth2/deviation/{id}`.
- Client-credentials tokens see public, non-mature content only. **Never ship a shared client id/secret in the APK.**

**Mode C: RSS fallback** **[VERIFY]**: `https://backend.deviantart.com/rss.xml?type=deviation&q=gallery:{u}` (offset paging, `media:content` image). May not reach deep into 20k+ galleries.

**oEmbed:** `https://backend.deviantart.com/oembed?url={url}` → `author_name`.

**Feeds (FR-7)** **[VERIFY all]**
- *Popular / Latest (anonymous):* the official API documents `GET /api/v1/oauth2/browse/popular` and `GET /api/v1/oauth2/browse/newest` (offset/limit paging, token required). The website's own `/popular` and `/newest` pages load through internal JSON endpoints that can be found in the network tab. RSS search (`https://backend.deviantart.com/rss.xml?q=...`) is an anonymous fallback.
- *Followed feed (logged in):* the website's Watch feed (`https://www.deviantart.com/watch/deviations`) loads JSON for the logged-in user; the official equivalent (`GET /api/v1/oauth2/browse/deviantsyouwatch`) needs a **user** OAuth token (authorization-code grant), **not** the client-credentials token from Mode B. Implement the followed feed via the website endpoint + shared WebView cookies. The official-API route for it is out of scope.

**Decision rule (Phase 0):** choose the first mode that, **unauthenticated**, (a) pages ≥24 items/request, (b) works at offsets ≥ 25,000, (c) supports both All and folder listing, (d) exposes title, publish time, type and a per-artwork URL, and (e) exposes full-size image URLs (list or single-fetch). Record the choice + sample JSON in `docs/API_FINDINGS.md` and `fixtures/`.

---

## 7. Architecture & file layout

```
src/all/deviantart/
├── build.gradle
├── AndroidManifest.xml                 # (stretch: deep link)
├── res/mipmap-*/ic_launcher.png        # simple original icon (do not copy the official logo)
└── src/eu/kanade/tachiyomi/extension/all/deviantart/
    ├── DeviantArt.kt          # HttpSource + ConfigurableSource
    ├── DeviantArtUrl.kt       # input -> Resolved (pure, unit-testable)
    ├── DeviantArtApi.kt       # endpoints, paging, token/CSRF handling
    ├── GalleryCache.kt        # disk cache, crawl, incremental merge
    ├── ChapterLedger.kt       # permanent id -> first-seen path ledger (FR-13)
    ├── ImageSelector.kt       # rendition ranking, aspect check, quality upgrade (pure, FR-16)
    ├── Feeds.kt               # Popular / Latest / Followed requests, paging, login check
    ├── Dto.kt                 # @Serializable models (all fields nullable/defaulted)
    ├── Batching.kt            # Appendix A only (optional)
    └── UrlActivity.kt         # (stretch)
docs/API_FINDINGS.md
fixtures/*.json
```

**build.gradle**
```groovy
ext {
    extName = 'DeviantArt'   // NEVER rename: Mihon derives the source id from name + lang; renaming orphans every library entry
    extClass = '.DeviantArt'
    extVersionCode = 1       // bump on EVERY build you install over an older one
    isNsfw = false   // revisit if mature support is enabled
}

apply from: "$rootDir/common.gradle"
```

**URL resolution skeleton (`DeviantArtUrl.kt`)** (rewritten in v2.5 for FR-15)
```kotlin
data class GalleryRef(val user: String, val folderId: String? = null) // folderId == null -> All gallery

sealed interface Resolved {
    data class Gallery(val ref: GalleryRef) : Resolved
    data class NeedsOembed(val url: String) : Resolved
    data object Invalid : Resolved
}

/** The ONLY place that builds a stored SManga.url (FR-15 item 2). */
fun entryUrl(ref: GalleryRef): String = "/${ref.user}/gallery/${ref.folderId ?: "all"}"

/** The ONLY place that reads a stored SManga.url (any legacy shape) or a pasted link. Null = unrecognised. */
fun normalizeEntryUrl(stored: String): GalleryRef? {
    val s = stored.trim()
    val candidate = if (s.startsWith("/")) "https://www.deviantart.com$s" else s
    return (DeviantArtUrl.resolve(candidate) as? Resolved.Gallery)?.ref
}

object DeviantArtUrl {
    private val RESERVED = setOf(
        "tag", "search", "topic", "topics", "watch", "shop", "join", "about", "developers",
        "checkout", "settings", "messages", "notifications", "daily-deviations", "popular",
        "newest", "core-membership", "studio", "users", "today", "deviation", "art", "journal",
    )
    private val OEMBED_FIRST_SEGMENTS = setOf("art", "deviation") // no username in the path
    private val NON_USER_SUBDOMAINS = setOf("www", "m", "backend", "api")
    private val USER = Regex("^[A-Za-z0-9][A-Za-z0-9-]{1,}$")

    // scheme optional; host (deviantart.com / fav.me with any subdomains); optional path; optional ?query / #fragment
    private val LINK = Regex(
        """^(?:https?://)?((?:[a-z0-9-]+\.)*(?:deviantart\.com|fav\.me))(/[^?#\s]*)?(?:[?#].*)?$""",
        RegexOption.IGNORE_CASE,
    )

    /** First whitespace-separated token that mentions deviantart.com or fav.me, punctuation trimmed. */
    private fun extractLink(q: String): String? =
        q.split(Regex("\\s+"))
            .firstOrNull { it.contains("deviantart.com", true) || it.contains("fav.me", true) }
            ?.trimStart('(', '<', '"', '\'')
            ?.trimEnd('.', ',', ';', ':', '!', '?', ')', ']', '>', '"', '\'')

    private fun withScheme(u: String) = if (u.startsWith("http", true)) u else "https://$u"

    fun resolve(raw: String): Resolved {
        val q = raw.trim()
        if (q.startsWith("user:", true)) return gallery(q.substring(5).trim(), emptyList())
        if (q.startsWith("@")) return gallery(q.substring(1).trim(), emptyList())

        val link = extractLink(q)
        if (link != null) {
            val m = LINK.find(link) ?: return Resolved.Invalid
            val host = m.groupValues[1].lowercase()
            val segs = m.groupValues[2].split('/').filter { it.isNotEmpty() }

            if (host == "fav.me") return Resolved.NeedsOembed(withScheme(link))
            if (host == "deviantart.com" || host == "www.deviantart.com" || host == "m.deviantart.com") {
                val first = segs.firstOrNull() ?: return Resolved.Invalid
                if (first.lowercase() in OEMBED_FIRST_SEGMENTS) return Resolved.NeedsOembed(withScheme(link))
                if (first.lowercase() in RESERVED) return Resolved.Invalid
                return gallery(first, segs.drop(1))
            }
            // legacy {user}.deviantart.com/... : the path is already relative to the user
            val sub = host.removeSuffix(".deviantart.com")
            if (host.endsWith(".deviantart.com") && '.' !in sub && sub !in NON_USER_SUBDOMAINS) {
                return gallery(sub, segs)
            }
            return Resolved.Invalid
        }
        return if (USER.matches(q)) gallery(q, emptyList()) else Resolved.Invalid
    }

    // `rest` = path segments after the username, e.g. ["gallery","12345","slug"], ["art","foo-999"], or [].
    private fun gallery(user: String, rest: List<String>): Resolved {
        if (!USER.matches(user)) return Resolved.Invalid
        val folder = rest.getOrNull(1)?.takeIf {
            rest[0].equals("gallery", ignoreCase = true) && it.isNotEmpty() && it.all(Char::isDigit)
        }
        // artwork links, /gallery, /gallery/all, /gallery/scraps and /favourites all map to the All gallery
        return Resolved.Gallery(GalleryRef(user, folder))
    }
}
```
Required tests: every row of the FR-15 item 9 table, plus `normalizeEntryUrl(entryUrl(ref)) == ref` for All and folder refs.

**Cache merge skeleton (`GalleryCache.kt`, pure part)**
```kotlin
data class CachedItem(val id: String, val title: String, val path: String, val publishedMs: Long)

/** `page` is newest-first. Returns new items (newest-first) and whether a known id was reached. */
fun newItemsFromPage(known: Set<String>, page: List<CachedItem>): Pair<List<CachedItem>, Boolean> {
    val fresh = ArrayList<CachedItem>()
    for (item in page) {
        if (item.id in known) return fresh to true
        fresh += item
    }
    return fresh to false
}

/** chapter_number counted from the oldest, 1-based, for a newest-first list. */
fun chapterNumber(indexNewestFirst: Int, total: Int): Float = (total - indexNewestFirst).toFloat()
```

**Main class outline (`DeviantArt.kt`)** (adapt signatures to the template's lib version)
```kotlin
class DeviantArt : HttpSource(), ConfigurableSource {
    override val name = "DeviantArt"
    override val baseUrl = "https://www.deviantart.com"
    override val lang = "all"
    override val supportsLatest = true

    override val client = network.cloudflareClient.newBuilder()
        // rate limit API + CDN hosts, 429 backoff interceptor, optional auth interceptor
        .build()

    override fun headersBuilder() = super.headersBuilder().set("Referer", "$baseUrl/")

    // Search   = DeviantArtUrl.resolve(query) -> GalleryRef -> validate -> single SManga, else empty
    // Details  = profile (+ folder metadata) -> SManga
    // Chapters = GalleryCache.load/crawl/incremental -> one SChapter per artwork, newest first
    // Pages    = fetch single deviation fresh -> Page(s) with imageUrl
    // Popular  = Feeds.popular(page)  -> unique authors -> All-gallery entries (artwork thumbnail as cover)
    // Latest   = Feeds.latest(page)   -> same mapping
    // Search   = link/username -> gallery | "feed:followed" or Feed filter -> Feeds.followed(page) (login required)
    // Filters  = Header + Select("Feed": None / Latest from followed Deviants)
}
```
Use the template's `keiyoushi.utils` helpers (`parseAs<T>()`, `getPreferencesLazy`) where available, and `kotlinx.serialization` with `ignoreUnknownKeys = true` (DA JSON changes often). `SManga.url` and `SChapter.url` must be **relative** paths.

---

## 8. Error handling matrix

| Situation | Behaviour |
|-----------|-----------|
| Unrecognised search text | Empty result |
| User or folder not found / deactivated | Exception: "Gallery not found or unavailable" |
| Gallery empty or all items filtered | Entry loads with zero chapters |
| HTTP 429 | Backoff + retry (≤3), then readable error; partial crawl cache is kept for resume |
| HTTP 403 | Classified per FR-11 (Cloudflare check, rate limit, image token, mature gate, restricted gallery, bad folder id, CSRF, bad session) with cause + endpoint label; never a bare "Error: 403" |
| Mature/premium artwork, no login | Stays in the chapter list; page list throws a clear "not available without login" |
| JSON shape changed | "DeviantArt response format changed" (never NPE); DTO fields nullable |
| Image token expired | Mihon retry re-requests the page list → fresh single-deviation fetch |
| Corrupt cache file | Delete the crawl cache and re-crawl (resumable); the chapter ledger is kept. A corrupt ledger is renamed `.bad` and rebuilt from the crawl cache |
| Folder-name lookup fails | Entry still loads; title `{user} – Folder {id}`; chapters listed normally |
| Upgraded or original image request fails, or exceeds the size guard | Silently use the next best valid rendition (logged "reduced quality"); never an error for the reader |
| Ledger id missing from the fresh crawl | Re-emitted from the ledger, never dropped; if impossible, a specific error and never a shorter list |
| Followed feed while logged out / session expired | Exception: "Log in to DeviantArt: open this source in WebView (globe icon), sign in, then try again." |
| Feed page entirely filtered out | Auto-fetch next page (max 3) |

---

## 9. Non-functional requirements

- **Search by link:** result in < 3 s for artwork/profile/gallery links (no oEmbed needed).
- **First full crawl, 20k artworks:** completes in ≲ 10 min with ≤ ~900 requests, resumable after interruption.
- **Refresh after first crawl:** ≤ 2 requests, < 3 s.
- **Page list for one artwork:** < 3 s, ≤ 2 requests.
- **Memory:** bounded; no raw JSON retained beyond one API page.
- **Correctness at scale:** galleries of 0, 1, 23, 24, 25, 20,000 and 50,000+ items; no duplicate or missing artworks between pages (verify by id).
- **Resilience:** no `!!`; DTOs nullable/defaulted.
- **Privacy:** no telemetry, no credential handling.
- **Quality:** lint passes; pure functions unit-tested.

---

## 10. Definition of done

1. Pasting a real artwork link yields the artist's **All gallery** entry; pasting a real folder link yields **that folder** entry.
2. A real public account with 20k+ gallery items can be added; the chapter list has one chapter per artwork with titles and dates, in gallery order.
3. A second refresh of that entry costs ≤ 2 requests; a newly uploaded artwork appears as a new top chapter.
4. Opening the first, a middle and the newest chapter shows the correct image(s).
5. Unit tests for URL resolution and cache merge pass.
6. `./gradlew :src:all:deviantart:assembleDebug` produces an APK that installs and runs in Mihon.
7. Browse shows working **Popular** and **Latest** tabs. With a logged-in WebView session, **Filter → Feed → Latest from followed Deviants** (or search `feed:followed`) shows the watch feed; when logged out it shows the login message.
8. The artworks and galleries that previously failed with 403 either load or fail with a specific classified message; no bare "Error: 403" remains.
9. Galleries added to the Mihon library by earlier builds still refresh: new artworks appear as new chapters, existing chapters keep their read progress, and no entry fails silently with an empty list.
10. **Read history is preserved.** Using the user's backup (or a fixture with the same URL shapes), the Mihon-sync simulator reports zero removed chapters and zero lost read flags; updating never creates a second library record.
11. **Galleries are complete and uncropped.** For the test galleries, chapters == items returned by the listing, matching DeviantArt's reported count within the documented tolerance, and a tall, a wide, a square and an animated artwork are displayed whole.
12. **Folder links work.** Every row of the FR-15 table passes; pasting a real folder link gives an entry titled with the folder name whose chapters equal that folder's contents (not the All gallery); the folder and All entries of one artist use separate caches and ledgers.
13. **Ledger proven.** After running `clear_cache`, deleting the crawl cache, and updating while logged out (with mature items previously listed while logged in), the returned list still contains every id in the ledger with unchanged chapter URLs, and the simulator reports zero removed chapters.
14. **Pages are whole on the device.** The tall, wide, square and GIF artworks display whole in Mihon's reader with default reader settings; the README lists the settings that can change this.
15. **Full quality.** For the 5 FR-16 test artworks, delivered pixels meet FR-16 item 5, `docs/QUALITY.md` exists, and the `image_quality` preference switches between Best available and Standard without changing the chapter list.

---

## 11. Phased execution plan (stop and report after every phase)

## 11a. Bug-fix track: 403 errors (use this first if the extension already exists)

1. **Instrument first:** implement `HttpGuard` (FR-11) and the `debug_log` preference so failures reveal their cause.
2. **Reproduce:** pick at least 3 chapters and 3 galleries that currently fail. For each, record which request fails (gallery listing, single-artwork fetch, or image CDN), the status, whether it is consistent or intermittent, and whether it happens logged out, logged in, or both. Also open the same item in a normal browser to see whether it is mature-flagged, private, or fine.
3. **Diagnose using the FR-11 table.** Hypotheses to test, most likely first: (a) the image URL was altered or its token is missing/expired/not valid for that size; (b) the gallery/artwork request is missing required headers, CSRF or bootstrap cookies; (c) rate limiting appearing as 403 during the crawl; (d) Cloudflare challenge; (e) mature or restricted content without a login; (f) wrong folder id mapping; (g) stale session cookies.
4. **Fix per cause** and re-test the same 6+ items; write `docs/403_CASES.md` (item, cause, fix, result).
5. **Add regression tests** from recorded fixtures, then continue the normal phases.

## 11b. Bug-fix track: galleries already in the library do not update (use this if it applies)

1. **Instrument:** add the FR-12 item 10 logging. On a device/emulator with Mihon, trigger a refresh of one **old** library entry (pull down on the entry page, then a global library update) and read the log (for example `adb logcat -s DeviantArt`). Record: the stored entry URL shape, whether `getMangaDetails`/`getChapterList` are called, and the outcome (exception / empty list / success).
2. **Test these hypotheses, most likely first:** (a) the stored entry URL is a legacy shape that the current code does not parse, so it returns an empty list or throws; (b) the stored chapter URLs use a different format than the current build emits, causing mismatches, duplicates or removals; (c) the cache is missing, purged, from an incompatible schema, or has a stale `complete = true`; (d) the incremental stop condition is wrong (never reaches a known id, or stops too early); (e) the first rebuild for an old entry is killed by timeouts, rate limiting or 403 during the library update; (f) the source id or signing key changed ("source not installed", or the APK would not install over the old one); (g) `status`/`update_strategy` makes Mihon skip the entry.
3. **Fix per cause** following FR-12; write `docs/COMPAT.md` (entry-URL shapes and chapter-URL formats per released build).
4. **Regression tests:** legacy entry-URL normalization table, legacy chapter-URL parsing, cache schema upgrade/rebuild, incremental stop condition, time-budget resume.
5. **Mihon-side settings that can also stop updates (not a code bug):** Settings → Library → Global update → "Skip updating entries" options (for example *have unread chapters* skips galleries that have thousands of unread chapters). Document this in the README.

## 11c. Bug-fix track: read history overwritten and incomplete galleries (use this if it applies)

**Before anything else, protect the user's data (tell the user):** create a manual Mihon backup (Settings → Data and storage → Create backup) and temporarily turn off automatic library updates (Settings → Library → Global update → Automatic update: Off). Test on one duplicate entry, not on the whole library. The agent must never touch the user's Mihon data directly; it only works with a backup file the user provides locally.

1. **Read history.** (a) From git history and, if provided, the user's `.tachibk` backup, extract the entry-URL and chapter-URL shapes this source stored (FR-13 item 2). (b) Build the Mihon-sync simulator (FR-13 item 5). (c) Run it with the old URLs against the current build's output and list removed / new / read-lost chapters with the reason. Likely causes, most likely first: the chapter URL format changed; the cache was rebuilt or its schema was upgraded and first-seen paths were lost; a partial or filtered list was returned; `skip_non_images` or another preference changed the listed items; `chapter_number` was 0, negative, duplicated or shifted; the entry URL was normalized into a new library record; `clear_cache` or a cache rebuild lost the first-seen paths (the ledger, FR-13 item 7, prevents this); a logged-in crawl listed mature items that a later logged-out crawl omitted (the superset invariant, FR-13 item 8, prevents this). (d) Fix per FR-13 and re-run until zero removed / zero read-lost.
2. **Missing artworks.** (a) For a gallery that looks incomplete, compare the count on DeviantArt in a browser with the crawled count and the chapter count. (b) Find where items disappear: filtering, offset handling, de-duplication, the `hasMore` stop condition, an offset ceiling, mature or premium items hidden while logged out, resume overlap, or the fallback folder crawl. (c) Fix per FR-14 and write `docs/COMPLETENESS.md`.
3. **Cropped images.** Check which rendition URL is used for a few affected artworks (log the rendition type and its width/height vs the original, never the token). Fix per FR-14 item 6 using a very tall, a very wide, a square image and a GIF as tests.
4. **Regression tests:** sync simulator, completeness invariants (chapters == listed items, using fixtures), pagination edge cases (offset ceiling, overlap), and the aspect-ratio check.

## 11d. Bug-fix track: a folder link does not open that folder (use this if it applies)

1. **Tests first.** Write the FR-15 item 9 table as JVM tests and run it against the **current** code. The failing rows show which link shapes are broken. Do not change code before this.
2. **Trace one real folder link end to end** (pick a public artist with at least 2 folders): pasted link, `resolve`, `entryUrl`, stored `SManga.url`, `getMangaDetails`, `getChapterList`, API request. With `debug_log` on, record the parsed `GalleryRef`, the stored entry URL, and the request label with parameter **names** (never tokens). Note the first step where the folder id is lost or changed.
3. **Test these hypotheses, most likely first:** (a) the link is not recognised (no scheme, text around it, mixed case, `m.` host); (b) the folder id is lost between search and details (the stored URL has no folder, or `getMangaDetails` / `getChapterList` do not use `normalizeEntryUrl`); (c) the cache or ledger file name ignores the folder, so the folder entry reads the All cache; (d) wrong API parameter (`all_folder=true` sent for a folder, or an id format the API rejects); (e) a failed folder-name lookup turned into "Gallery not found"; (f) the folder belongs to another user than the one in the link; (g) a favourites or scraps link mapped to All as designed.
4. **Fix per FR-15**, keep the new tests, and verify on 3 real folders (a small one, a large one, and a nested one if nested folders exist **[VERIFY]**): the entry title shows the folder name, the chapter count equals the folder's reported size, and the chapters differ from the All gallery.
5. **Do not disturb existing library entries.** Re-run the Mihon-sync simulator (FR-13 item 5): the folder fix must not change any previously stored entry URL or chapter URL.
6. **Write `docs/FOLDER_LINKS.md`:** the link shapes tested, the root cause found, the fix, and the verified mapping between the website's numeric folder id and the API's folder id.

## 11e. Bug-fix track: artworks are not shown at full quality (use this if it applies)

1. **Measure first.** For 5 affected artworks (FR-16 item 6) log, with `debug_log` on, the chosen rendition source, delivered size, original size, `q_` value and `Content-Length` (never tokens). Open the same artworks in a normal browser and note the size and quality of its full view.
2. **Test these hypotheses, most likely first:** (a) a smaller rendition is chosen (first list entry, a fixed `fullview` label, or a preview); (b) the chosen URL's transform limits size or quality; (c) the listing media is reduced and the single-artwork fetch has a bigger one; (d) a downloadable original exists but is unused; (e) the `Accept` header changes the encoding; (f) an interceptor or Mihon setting (Data saver, reader scaling) lowers quality; (g) a GIF is shown through a static rendition; (h) DeviantArt caps the public size (expected).
3. **Fix per FR-16** (pixel ranking, original first when allowed, guarded upgrade only if Phase 0 proves it works), then re-measure the same 5 artworks. Do not change any chapter URL or the chapter list (FR-13); this fix affects `getPageList` and image requests only.
4. **Write `docs/QUALITY.md`** and add unit tests for `pickRendition` (fixtures with several `types[]` sizes, a mismatched aspect ratio, a missing token, a tie) and `upgradeRendition` (only the quality value changes; the fallback URL is untouched).
5. **Check the device:** compare the artwork in Mihon with the browser's full view using default reader settings, then with Data saver off and different scale types; record which setting, if any, lowers quality.

### Phase 0: API discovery (no extension code)
- Using the Antigravity browser + terminal, test Modes A/B/C (Section 6).
- Find: one public account with **>20,000** gallery artworks, one with a small gallery with multiple folders, one with an empty gallery, a nonexistent username.
- Answer with evidence: max `limit`; offset ceiling (works ≥ 25,000?); pagination fields; **All vs folder listing params**; **website numeric folder id ↔ API folder id**; folder name source; per-artwork fields (title, url, publish time, type, mature flag); how to filter non-images; **single-deviation fetch** returning fresh full-size image URL; image token lifetime; max safe request rate before 429; Cloudflare/header needs; oEmbed output; **measure a real full crawl** (time + request count) on the 20k+ account.
- **Folder analysis:** for 3 real folders (small, large, and nested if they exist) record: the numeric id in the website URL, every id field in the folder list, which `folderid` value the gallery-contents endpoint accepts, the folder's reported size, whether a parent folder's contents include its sub-folders' items, and the response when a folder id belongs to a different user.
- **Quality analysis:** for 5 artworks (small, tall, wide, GIF, high-resolution downloadable) list every rendition the API exposes (type name, width, height, path template, quality value, token scope); fetch each and record the real delivered size and bytes; test whether the same token accepts a higher `q_` value, whether a larger rendition than `fullview` exists, how a downloadable artwork exposes its original file, whether `Accept` changes the encoding, and what a logged-out browser's "full view" delivers for comparison.
- **403 analysis:** reproduce 403s deliberately and document each cause: (a) an image URL with the token removed, with the token but altered width/height/quality, with an expired token, and without Referer/UA; (b) gallery and deviation endpoints with and without CSRF/bootstrap cookies and headers; (c) a mature-flagged artwork and a mature/restricted folder, anonymously; (d) raise the request rate until the first 403/429 and record the threshold, headers (e.g. `Retry-After`) and any Cloudflare markers; (e) a stale/invalid cookie. Record which URL pattern/rendition is valid for each artwork type.
- Save raw samples to `fixtures/` and write `docs/API_FINDINGS.md` with the chosen mode and rationale.

**Phase 0b: logged-in discovery (user-assisted).** After 0a, the agent must **pause and ask the user to log in manually** in the Antigravity browser window. The agent must **never** ask for, receive, type or store the user's DeviantArt password, and must not write cookies, tokens or HAR files into the repo (add `*.har` and any cookie/env files to `.gitignore`; sanitize all fixtures). Find and document:
- Popular and Latest feed endpoints (test anonymously first), paging params, item fields.
- The followed-feed (Watch) endpoint, paging params, item fields.
- Which cookies indicate a logged-in session (names only, never values), and whether those cookies alone authenticate a plain HTTP client (test with curl using the session in a local-only environment variable that is never saved).
- Logged-out behaviour of the followed feed (status code / redirect / JSON error) so the extension can detect it.
- Whether Google/Facebook login works inside a mobile WebView, or whether username/password is required.

**Acceptance:** every **[VERIFY]** is resolved (confirmed / changed / dropped); working `curl` commands for: All gallery page, folder page, folder name, single deviation image, oEmbed, Popular, Latest, and the followed feed (cookie value redacted in docs).

### Phase 1: Scaffold
Module + icon + empty source that compiles. **Acceptance:** `assembleDebug` succeeds; APK installs; source appears in Browse.

### Phase 2: Link resolution + entry details
Implement `DeviantArtUrl`, search override (artwork → All, folder link → folder), validation, `getMangaDetails`. Unit tests for every row of the FR-2 table + negatives. **Acceptance:** artwork URL, profile URL, `/gallery`, `/gallery/{id}/{slug}`, `fav.me` and `user:name` each give the right single entry; scheme-less links, links inside pasted text, mixed-case paths and `m.` / subdomain hosts do too; `entryUrl` then `normalizeEntryUrl` round-trips for All and folder entries (FR-15); garbage gives nothing.

### Phase 3: Crawl + cache + chapter list
Implement `GalleryCache` (full crawl, resume, incremental refresh), mapping to chapters (no filtering), `clear_cache`. Unit tests for merge logic and resume overlap. **Acceptance:** on the 20k+ account, a full crawl completes; chapter count equals crawled count equals the count DeviantArt reports for that gallery (FR-14); zero duplicate ids; killing the app mid-crawl and retrying resumes; second refresh ≤ 2 requests. Record duration + request count.

### Phase 4: Pages + images
Single-deviation fetch, image selection, `imageRequest`. **Acceptance:** newest, middle and oldest chapters of the 20k+ account load the correct image(s); folder entry chapters match that folder's contents; every artwork/gallery previously listed in `docs/403_CASES.md` now loads or fails with a specific classified message; the FR-16 quality report is written and delivered pixels meet FR-16 item 5.

### Phase 5: Hardening + preferences
Rate limiting, 429 backoff, error matrix, preferences, optional Mode B. **Acceptance:** simulated 429/403 (each cause in FR-11)/404/corrupt-cache behave per Section 8; `HttpGuard` unit tests pass.

### Phase 6: Browse feeds (Popular, Latest, Followed)
Implement `Feeds.kt`, `supportsLatest = true`, the author-tile mapping with de-duplication and the auto-next-page rule, `getFilterList` with the Feed select, the `feed:followed` shortcut, search precedence (link → followed feed → empty), and logged-out detection with the exact error message. Unit tests for the tile mapping/de-dupe, search precedence, and login-state detection against fixtures.
**Acceptance:** Popular and Latest load and paginate anonymously; tapping a tile opens that artist's All gallery; logged out → followed feed shows the login message; after a WebView login it shows the watch feed and paginates; lint passes.

### Phase 6b (optional): Polish
Deep link (FR-10) and Appendix A batch mode.

### Phase 7: Packaging
APK + README (install, pasting links, All vs folder entries, first-load time warning, mature-content WebView tip, deleted-artwork limitation, clear-cache setting, favourites and scraps links opening the All gallery, the Mihon reader settings that can crop, split or shrink images, DeviantArt's public size limit for non-downloadable art, the `image_quality` preference, and the global-update skip options). **Acceptance:** fresh install works end-to-end from the README alone.

---

## 12. Test plan

**Automated (JVM):** rendition ranking `pickRendition` and `upgradeRendition` (FR-16: largest valid pixels wins, mismatched aspect ratio rejected, only the quality value changes, fallback untouched); URL resolver table (including folder/All outcomes and every row of FR-15 item 9); `entryUrl` / `normalizeEntryUrl` round trip; separate cache and ledger file names for folder vs All; ledger superset invariant (after `clear_cache`, after a logged-out crawl that omits items, after a renamed artwork); legacy library-URL normalization table (FR-12); cache schema upgrade/rebuild and time-budget resume; Mihon-sync simulator (read-history preservation); completeness invariants; rendition aspect-ratio check; `HttpGuard` 403/429 classification against recorded fixtures (Cloudflare HTML, expired-token CDN response, mature-gated JSON, rate-limit response); `newItemsFromPage`; `chapterNumber`; resume/de-dupe logic against a fake paging source (T = 0, 1, 23, 24, 25, 20,000, with uploads inserted mid-crawl); DTO parsing against `fixtures/*.json`.

**Manual (Mihon device/emulator):**
1. Paste artwork URL / profile URL / `/gallery` / folder URL / `fav.me` / `user:name` / garbage.
2. Add a 20k+ gallery; wait for the first load; open newest, middle, oldest chapters.
3. Pull-to-refresh and library update (should be near-instant).
4. Small multi-folder artist: All vs each folder entry.
5. Empty gallery, nonexistent user, deleted folder.
6. Kill the app mid-crawl, reopen, retry (resume).
7. Airplane mode and 429 messages are readable.
8. WebView login → mature items appear (if the account allows).
9. Popular and Latest tabs load and paginate; tapping a tile opens that artist's All gallery entry.
10. Logged out: Filter → Feed → Latest from followed Deviants (and the search `feed:followed`) shows the login message. After signing in through the WebView icon, the watch feed loads and paginates. Session expiry shows the same message again.
11. Re-test every artwork and gallery listed in `docs/403_CASES.md`, logged out and logged in, and with `debug_log` on to confirm the cause is classified correctly.
12. Library compatibility: with an entry added by an **earlier build**, pull to refresh and run a global library update. Confirm new artworks appear, read progress is kept, no duplicate chapters appear, and errors (if any) are specific. Repeat after installing a newer build over the older one (same signing key, higher `extVersionCode`).
13. Read history: on a **backed-up** library, mark several chapters of one gallery as read, run an update, and confirm the same chapters are still read, History still shows them, and no duplicate or "new" entry appears.
14. Completeness and cropping: open a gallery in a browser, compare its artwork count with the chapter count in Mihon, and open a very tall artwork, a very wide one, a square one and a GIF to confirm each is shown whole.
15. Folder links: paste each FR-15 table shape (including without `https://` and inside copied share text) for a real artist with several folders; confirm the entry title carries the folder name, the chapters match the folder in the browser, the All entry of the same artist is a separate entry, and both survive pull-to-refresh.
16. Ledger: with some chapters marked read in a folder entry, use the `clear_cache` preference, pull to refresh, then update while logged out; confirm read marks, History and the chapter count are unchanged. Check the reader with default settings and with crop borders / split tall images toggled.
17. Quality: for the 5 FR-16 artworks compare Mihon's display with DeviantArt's full view in a browser (zoom into fine detail); switch `image_quality` between Best available and Standard and confirm the sharpness and size change while the chapter list stays the same; confirm a GIF animates.

---

## 13. Risks, constraints & legal notes

- **Unofficial endpoints can change without notice.** Keep parsing in `DeviantArtApi.kt`/`Dto.kt`, nullable DTOs, clear errors.
- **Terms of Service:** DA's terms restrict automated scraping; artwork is copyrighted by its creators. Personal viewing only: keep requests low-rate, do not circumvent paywalls, premium content, download protection, or login/age gates, do not redistribute artwork. The developer must assess ToS/legal risk before any public distribution.
- **Heavy first crawl** (~834 requests for 20k items) is a notable load on DA's servers; it must be one-time, rate limited, resumable and cached.
- **Mihon with ~20k chapters** can feel slow in the chapter list; use Appendix A mode if it is unusable.
- **Cloudflare/anti-bot** may block requests; document the WebView workaround.

---

## 14. Agent Rules (copy into Antigravity workspace rules)

1. Follow Sections 0 and 11 strictly: **do Phase 0 first**; no extension code until `docs/API_FINDINGS.md` exists.
2. Build inside keiyoushi/extensions-source and match the style of 2–3 comparable `src/all/*` extensions before coding.
3. Kotlin only; `kotlinx.serialization` with `ignoreUnknownKeys = true`; no `!!`; no blocking calls on the main thread.
4. Keep URL parsing, cache merge and chapter numbering as **pure functions** with unit tests.
5. **One chapter per artwork. Do not batch artworks** (except the optional Appendix A mode behind a preference).
6. Artwork link and profile link → **All gallery**; gallery folder link → **that folder**. Do not return multiple entries from a link.
7. Never store image URLs/tokens in the cache; resolve images fresh per chapter.
8. Never return a partial chapter list as complete; keep partial crawl state and resume.
9. Never embed API keys/secrets or DA credentials; never log tokens or cookie values. **Never ask the user for their DeviantArt password**; when logged-in discovery is needed, pause and let the user sign in manually. Never commit cookies, tokens or HAR files (use `.gitignore`) and sanitize fixtures.
10. Respect rate limits and backoff on 429; no parallel hammering of DA.
11. Do not bypass paywalls, mature-content gates, or download protection.
12. After each phase: run build + tests, paste real output, list remaining **[VERIFY]** items, wait for approval.
13. If a requirement conflicts with observed API behaviour, record it in `docs/API_FINDINGS.md`, propose the smallest change, and ask before deviating.
14. Never surface a bare HTTP status to the user; classify failures per FR-11. Use signed image URLs exactly as the API returns them (never edit or rebuild them) and fetch fresh tokens per chapter. Never log full URLs with tokens, cookies, or auth headers.
15. **Backward compatibility is mandatory.** Never change `extName`, `lang`, the package, the signing key, the entry-URL shapes or the chapter-URL format that earlier builds stored; accept legacy shapes (FR-12) and record them in `docs/COMPAT.md`. Bump `extVersionCode` on every installed build.
16. Never return an empty or partial chapter list for a valid library entry; throw a specific error instead.
17. **Never change a chapter URL that has ever been emitted.** Never remove a previously listed chapter. Verify every change with the Mihon-sync simulator before shipping (FR-13). Never touch the user's real Mihon data; work only from a backup file the user provides, and never commit it.
18. **No silent filtering.** Every item the gallery listing returns becomes a chapter. Measure and report completeness (FR-14).
19. **Show whole artworks.** Never use thumbnail, preview or cropped renditions as pages; check aspect ratio against the original.
20. **One builder, one parser.** Every entry URL is built with `entryUrl()` and read with `normalizeEntryUrl()`; cache and ledger file names include the folder id. Prove folder links with the FR-15 table before shipping.
21. **Maintain the chapter ledger.** Never delete it (not even in `clear_cache`); never return a list that omits a ledger id; take the chapter URL of every known id from the ledger.
22. **Judge a crop by aspect ratio, not by the URL.** A `/v1/fill/` segment alone is not a crop. Reject renditions whose aspect ratio differs from the original's by more than ~2%; log the rendition kind and its size vs the original.
23. **Pick the rendition with the most pixels** (FR-16), the original/download file first only when the artist allows downloads. Test any quality-parameter upgrade in Phase 0; use it only if proven, only on the quality value, and always with a fallback to the as-provided URL.
24. **Never claim full quality without measurement.** Produce `docs/QUALITY.md` with delivered vs original sizes; never bypass download protection to reach an original.

---

## 15. Kickoff prompt (paste into Antigravity Agent Manager)

```
You are building a Mihon extension called "DeviantArt" in Kotlin, inside a fork of
keiyoushi/extensions-source. Read PRD.md (v2.6) fully first and follow it phase by phase.

Key behaviour:
- Pasting a DeviantArt ARTWORK link (or profile link) into Mihon's Search shows the artist's
  "All" gallery as one entry.
- Pasting a GALLERY (folder) link shows that specific gallery as one entry.
- Every artwork is its OWN chapter (titled, dated, in gallery order). Do not merge/batch artworks.
- It must work for galleries with 20,000+ artworks using a resumable crawl + on-disk cache +
  incremental refresh (PRD Section 5).
- Browse must offer Popular, Latest, and "Latest from followed Deviants". Mihon has no third tab,
  so the followed feed is a Filter option (plus the search text `feed:followed`). It needs the user
  logged in through Mihon's WebView (shared cookies); detect the logged-out state and show a clear
  message (PRD FR-7).

Start with Phase 0 only: use the browser and terminal to discover and verify DeviantArt's
gallery/folder/deviation/oEmbed/RSS behaviour, find a public account with >20,000 gallery
artworks, save sample JSON to fixtures/, measure a real full crawl, and write
docs/API_FINDINGS.md resolving every [VERIFY] item. Do not write extension code yet.
Phase 0 has two parts. After 0a (anonymous), STOP and ask me to log in manually in the browser
for 0b (feeds that need a login). Never ask me for my DeviantArt password and never store cookies,
tokens or HAR files in the repo.

When Phase 0 is complete, stop, present the findings and the chosen API mode, and wait for my
approval. After each later phase, run build and tests, summarise real results, and wait for approval.

If the extension already exists and some chapters or galleries fail with "Error: 403", run the
Bug-fix track (PRD Section 11a) BEFORE continuing with the normal phases.
Likewise, if galleries already in the Mihon library no longer update, run Section 11b first
(legacy URL shapes, stable chapter URLs, versioned persistent cache, resumable crawl, FR-12).
If updating a gallery resets its read history, or artworks are missing or cropped, run Section 11c
first (FR-13 and FR-14). Ask me to create a Mihon backup before testing. Work only from a backup
file I provide; never commit it, and never touch my Mihon data directly.
If pasting a gallery-folder link does not open that folder, run Section 11d first (FR-15), writing the
unit-test table before any code change. Maintain the chapter ledger (FR-13 items 7 to 10) so that
updates never delete previously listed chapters.
If artworks are not shown at full quality, run Section 11e first (FR-16): measure delivered vs original
size on 5 artworks before changing code, and do not change any chapter URL or the chapter list.
```

---

## Appendix A: Optional "Batches" chapter mode (performance fallback, P2)

Only if `chapter_mode = Batches`. Merges N consecutive artworks into one chapter (default 48; options 24/48/96/192). Needs the total count `T` (use a count field if the API has one, else exponential + binary offset probing, ~16 requests) and no crawl.

```kotlin
data class BatchWindow(val newestOffset: Int, val count: Int)

fun chapterCount(total: Int, size: Int) = if (total <= 0) 0 else (total + size - 1) / size

fun windowFor(batchIndex: Int, size: Int, total: Int): BatchWindow {
    val oldestEnd = minOf((batchIndex + 1) * size, total)
    val oldestStart = batchIndex * size
    return BatchWindow(newestOffset = total - oldestEnd, count = oldestEnd - oldestStart)
}
```
Chapter URL: `/{user}/gallery/{all|folderId}?batch={i}&size={B}`; name `"Artworks {from}–{to}"`; chapter 1 = oldest batch; pages inside a chapter ordered oldest → newest. Not the default because it does not match the gallery's one-artwork-per-item layout.
