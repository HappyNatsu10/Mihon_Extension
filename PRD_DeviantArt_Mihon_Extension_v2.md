# PRD: DeviantArt Source Extension for Mihon (v2.2)

**Doc version:** 2.2 (supersedes v1.0, v2.0 and v2.1)  |  **Target builder:** Google Antigravity  |  **Language:** Kotlin  |  **Output:** Installable Mihon extension APK

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
| U4 | I open an entry and see each artwork as a separate chapter named by title, newest first, like the gallery. | P0 |
| U5 | I add a 20k+ gallery to my library; the first load completes (one-time), later refreshes are fast. | P0 |
| U6 | New uploads appear as new chapters on library update. | P0 |
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
| `https://{user}.deviantart.com/...` (legacy subdomain, `user != www`) | Same rules applied to the path |
| `https://www.deviantart.com/deviation/{id}`, `https://www.deviantart.com/art/{slug}-{id}` (no user in path), `https://fav.me/d{base36}` | oEmbed lookup → `author_name` → **All gallery** (`https://backend.deviantart.com/oembed?url={urlEncoded}`) **[VERIFY]** |
| `user:{name}` or `@{name}` | All gallery |
| Bare text matching the username regex | Validate as a username → All gallery |

Rules:
- Reserved first path segments are **not** usernames: `tag, search, topic, topics, watch, shop, join, about, developers, checkout, settings, messages, notifications, daily-deviations, popular, newest, core-membership, studio, users, today, deviation, art, journal`. `art` and `deviation` go to the oEmbed path.
- Username regex: `^[A-Za-z0-9][A-Za-z0-9-]{1,}$` (case-insensitive; store the canonical casing returned by DA).
- Only `gallery/{numeric id}` selects a folder. Anything else after the username (including `gallery/scraps`, `/favourites`) resolves to **All gallery** in v1.
- Output: a `MangasPage` with **exactly one** `SManga`, `hasNextPage = false`, populated enough to add from the search grid.
- Unresolvable input → empty result (never crash).

### FR-3 Entry validation + details (`getMangaDetails`)
- Validate the user (and folder, if any) exists.
- All gallery: `title = username`, `author = artist = username`, `thumbnail_url = avatar`, `description = bio/tagline + "Gallery: ~N artworks"` (N only if cheaply known), `status = ONGOING`, `genre = ["Gallery"]`, `initialized = true`.
- Folder: same, plus `title = "{username} – {folderName}"` and folder name in the description. Folder name/metadata comes from the folder listing endpoint **[VERIFY]**. Also **[VERIFY]** how the numeric folder id in the website URL maps to the API's folder id (official API uses UUID-style ids; website/napi URLs use numeric ids).
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
- `skip_non_images` (default true): literature, journals and videos are filtered out **at crawl time** so they never become empty chapters. Needs the deviation `type`/`isJournal`/media fields **[VERIFY]**.
- Empty gallery → empty list (not an error).

### FR-5 Page list (`getPageList`)
- Extract the deviation id (and user) from the chapter URL (trailing `-(\d+)` of the last segment, or `/deviation/(\d+)`).
- Fetch **fresh** data for that single artwork. **Do not** reuse image URLs from the cache, because they carry expiring tokens. Candidate endpoints **[VERIFY]**: an internal "extended fetch" JSON for one deviation (e.g. `/_napi/shared_api/deviation/extended_fetch?deviationid={id}&username={user}&type=art`), the official `GET /api/v1/oauth2/deviation/{id}`, or oEmbed as a last resort.
- Pages = the artwork's full-size image(s) (support multi-image deviations if they exist **[VERIFY]**). Set `Page.imageUrl` directly; `url` keeps the artwork path.
- No usable image (mature-gated, premium, non-image) → `Exception("This artwork is not available without login")` or a clear equivalent.
- `imageUrlParse` → `throw UnsupportedOperationException()` if `imageUrl` is always pre-filled.

### FR-6 Image requests
- `imageRequest` adds `Referer: https://www.deviantart.com/` and a realistic `User-Agent`.
- Prefer the largest public non-watermarked rendition ("fullview"/original public size). Animated GIFs: use the GIF rendition if exposed **[VERIFY]**.
- **Use image URLs exactly as the API provides them**, including the signed `token` query parameter. Do not edit width/height/quality path segments, strip the token, or rebuild the URL by hand: signed tokens usually authorise only specific paths/sizes, and a modified URL returns **403** **[VERIFY]**.
- **Fallback chain on an image 403:** (1) re-fetch the single artwork fresh (new token) and retry the same rendition once; (2) try the next-best rendition (`fullview` → `preview` → others), each with the token that came with it; (3) fail with a classified message (FR-11). Never reuse image URLs from the cache or from an earlier session.

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
- **Non-images:** apply `skip_non_images` to feed items. If a whole page is filtered out but more pages exist, automatically fetch the next page (max 3 attempts) so scrolling does not stop.
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
| `skip_non_images` | Switch | true | Hide literature/journals/videos |
| `clear_cache` | Preference (click action) | n/a | Deletes all cached galleries (forces a full re-crawl) |
| `chapter_mode` | List: "Each artwork" / "Batches" | Each artwork | Batches = Appendix A (performance fallback; P2) |
| `debug_log` | Switch | false | Redacted request/response diagnostics (FR-11) |
| `api_client_id` / `api_client_secret` | Text | empty | Optional official-API mode (Section 6, Mode B) |

### FR-9 Mature content
- Unauthenticated requests return only non-mature items. Mihon's HTTP client shares cookies with the app WebView, so a user who logs in via **Open in WebView** gets mature content and the **followed feed** (FR-7) automatically. The extension must **never** ask for, store, or transmit DA credentials. Gated items are skipped and logged, never crash.

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

---

## 5. Scaling design for 20,000+ artworks (one chapter per artwork)

### 5.1 Reality check
Mihon requires `getChapterList` to return **all** chapters at once, and chapter names/dates require reading every artwork's metadata. For 20,000 artworks at 24 per API page that is **~834 requests** on the first load. At a polite 2–3 requests/second that is **roughly 5–7 minutes, one time only**. After that, refreshes must cost **1–2 requests**. A 20k-chapter entry is also heavy for Mihon's UI; this is expected and accepted. (Appendix A is the escape hatch.)

### 5.2 Gallery cache (on disk)
- Location: `context.cacheDir/deviantart/{user}_{folderId|all}.json` (`context` via `Injekt.get<Application>()`, as other extensions do). Do not use SharedPreferences for big lists.
- Content: `{ "complete": Boolean, "items": [ {"id", "title", "path", "publishedMs"} ... ] }`, **newest-first**. **Never store image URLs/tokens.**
- Size target: ~100 bytes/item → ~2 MB for 20k items. Parse with streaming/`kotlinx.serialization` once per call; never hold raw API JSON longer than one page.

### 5.3 Algorithm
**First load (no cache) → full crawl**
1. `offset = 0`; loop: fetch page (≤24 items; confirm max `limit` in Phase 0), map to `CachedItem` (apply `skip_non_images`), append, `offset = nextOffset`, until `hasMore == false`.
2. Every ~20 pages, persist the cache with `complete = false` so an interrupted crawl can **resume**. Resume at `offset = max(0, items.size - 24)` (overlap) and de-duplicate by `id`, because uploads during the crawl shift offsets.
3. On success set `complete = true`, persist, return all chapters.
4. On network/429 failure after retries: **throw** (keep the partial cache). **Never return a partial list** as if complete.

**Later refreshes (cache complete) → incremental**
1. Fetch from `offset = 0` page by page **until a page contains an id already in the cache** (normally the very first page).
2. Prepend unseen items (de-dupe by id), persist, return the full list from cache.
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
    extName = 'DeviantArt'
    extClass = '.DeviantArt'
    extVersionCode = 1
    isNsfw = false   // revisit if mature support is enabled
}

apply from: "$rootDir/common.gradle"
```

**URL resolution skeleton (`DeviantArtUrl.kt`)**
```kotlin
data class GalleryRef(val user: String, val folderId: String? = null) // folderId == null -> All gallery

sealed interface Resolved {
    data class Gallery(val ref: GalleryRef) : Resolved
    data class NeedsOembed(val url: String) : Resolved
    data object Invalid : Resolved
}

object DeviantArtUrl {
    private val RESERVED = setOf(
        "tag", "search", "topic", "topics", "watch", "shop", "join", "about", "developers",
        "checkout", "settings", "messages", "notifications", "daily-deviations", "popular",
        "newest", "core-membership", "studio", "users", "today", "deviation", "art", "journal",
    )
    private val USER = Regex("^[A-Za-z0-9][A-Za-z0-9-]{1,}$")
    private val MAIN = Regex("""^https?://(?:www\.)?deviantart\.com/([^/?#]+)([/?#].*)?$""", RegexOption.IGNORE_CASE)
    private val SUB = Regex("""^https?://([A-Za-z0-9-]+)\.deviantart\.com([/?#].*)?$""", RegexOption.IGNORE_CASE)
    private val FAVME = Regex("""^https?://fav\.me/d[0-9a-z]+""", RegexOption.IGNORE_CASE)
    private val FOLDER = Regex("""^/gallery/(\d+)(?:[/?#]|$)""")

    fun resolve(raw: String): Resolved {
        val q = raw.trim()
        if (q.startsWith("user:", true)) return all(q.substring(5).trim())
        if (q.startsWith("@")) return all(q.substring(1).trim())
        if (FAVME.containsMatchIn(q)) return Resolved.NeedsOembed(q)
        MAIN.find(q)?.let { m ->
            val seg = m.groupValues[1]
            if (seg.lowercase() in RESERVED) return Resolved.NeedsOembed(q)
            return gallery(seg, m.groupValues[2])
        }
        SUB.find(q)?.let { m ->
            val sub = m.groupValues[1]
            if (!sub.equals("www", true)) return gallery(sub, m.groupValues[2])
        }
        return if (USER.matches(q)) all(q) else Resolved.Invalid
    }

    // `rest` is the path after the username, e.g. "/gallery/12345/slug", "/art/foo-999", or "".
    private fun gallery(user: String, rest: String): Resolved {
        if (!USER.matches(user)) return Resolved.Invalid
        val folder = FOLDER.find(rest)?.groupValues?.get(1)
        return Resolved.Gallery(GalleryRef(user, folder)) // artwork links have no folder -> All
    }

    private fun all(user: String) = gallery(user, "")
}
```

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
| Mature/premium artwork, no login | Skipped in listing if flagged; page list throws a clear "not available without login" |
| JSON shape changed | "DeviantArt response format changed" (never NPE); DTO fields nullable |
| Image token expired | Mihon retry re-requests the page list → fresh single-deviation fetch |
| Corrupt cache file | Delete and re-crawl |
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

---

## 11. Phased execution plan (stop and report after every phase)

## 11a. Bug-fix track: 403 errors (use this first if the extension already exists)

1. **Instrument first:** implement `HttpGuard` (FR-11) and the `debug_log` preference so failures reveal their cause.
2. **Reproduce:** pick at least 3 chapters and 3 galleries that currently fail. For each, record which request fails (gallery listing, single-artwork fetch, or image CDN), the status, whether it is consistent or intermittent, and whether it happens logged out, logged in, or both. Also open the same item in a normal browser to see whether it is mature-flagged, private, or fine.
3. **Diagnose using the FR-11 table.** Hypotheses to test, most likely first: (a) the image URL was altered or its token is missing/expired/not valid for that size; (b) the gallery/artwork request is missing required headers, CSRF or bootstrap cookies; (c) rate limiting appearing as 403 during the crawl; (d) Cloudflare challenge; (e) mature or restricted content without a login; (f) wrong folder id mapping; (g) stale session cookies.
4. **Fix per cause** and re-test the same 6+ items; write `docs/403_CASES.md` (item, cause, fix, result).
5. **Add regression tests** from recorded fixtures, then continue the normal phases.

### Phase 0: API discovery (no extension code)
- Using the Antigravity browser + terminal, test Modes A/B/C (Section 6).
- Find: one public account with **>20,000** gallery artworks, one with a small gallery with multiple folders, one with an empty gallery, a nonexistent username.
- Answer with evidence: max `limit`; offset ceiling (works ≥ 25,000?); pagination fields; **All vs folder listing params**; **website numeric folder id ↔ API folder id**; folder name source; per-artwork fields (title, url, publish time, type, mature flag); how to filter non-images; **single-deviation fetch** returning fresh full-size image URL; image token lifetime; max safe request rate before 429; Cloudflare/header needs; oEmbed output; **measure a real full crawl** (time + request count) on the 20k+ account.
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
Implement `DeviantArtUrl`, search override (artwork → All, folder link → folder), validation, `getMangaDetails`. Unit tests for every row of the FR-2 table + negatives. **Acceptance:** artwork URL, profile URL, `/gallery`, `/gallery/{id}/{slug}`, `fav.me` and `user:name` each give the right single entry; garbage gives nothing.

### Phase 3: Crawl + cache + chapter list
Implement `GalleryCache` (full crawl, resume, incremental refresh), mapping to chapters, `skip_non_images`, `clear_cache`. Unit tests for merge logic and resume overlap. **Acceptance:** on the 20k+ account, a full crawl completes; chapter count equals crawled count; zero duplicate ids; killing the app mid-crawl and retrying resumes; second refresh ≤ 2 requests. Record duration + request count.

### Phase 4: Pages + images
Single-deviation fetch, image selection, `imageRequest`. **Acceptance:** newest, middle and oldest chapters of the 20k+ account load the correct image(s); folder entry chapters match that folder's contents; every artwork/gallery previously listed in `docs/403_CASES.md` now loads or fails with a specific classified message.

### Phase 5: Hardening + preferences
Rate limiting, 429 backoff, error matrix, preferences, optional Mode B. **Acceptance:** simulated 429/403 (each cause in FR-11)/404/corrupt-cache behave per Section 8; `HttpGuard` unit tests pass.

### Phase 6: Browse feeds (Popular, Latest, Followed)
Implement `Feeds.kt`, `supportsLatest = true`, the author-tile mapping with de-duplication and the auto-next-page rule, `getFilterList` with the Feed select, the `feed:followed` shortcut, search precedence (link → followed feed → empty), and logged-out detection with the exact error message. Unit tests for the tile mapping/de-dupe, search precedence, and login-state detection against fixtures.
**Acceptance:** Popular and Latest load and paginate anonymously; tapping a tile opens that artist's All gallery; logged out → followed feed shows the login message; after a WebView login it shows the watch feed and paginates; lint passes.

### Phase 6b (optional): Polish
Deep link (FR-10) and Appendix A batch mode.

### Phase 7: Packaging
APK + README (install, pasting links, All vs folder entries, first-load time warning, mature-content WebView tip, deleted-artwork limitation, clear-cache setting). **Acceptance:** fresh install works end-to-end from the README alone.

---

## 12. Test plan

**Automated (JVM):** URL resolver table (including folder/All outcomes); `HttpGuard` 403/429 classification against recorded fixtures (Cloudflare HTML, expired-token CDN response, mature-gated JSON, rate-limit response); `newItemsFromPage`; `chapterNumber`; resume/de-dupe logic against a fake paging source (T = 0, 1, 23, 24, 25, 20,000, with uploads inserted mid-crawl); DTO parsing against `fixtures/*.json`.

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

---

## 15. Kickoff prompt (paste into Antigravity Agent Manager)

```
You are building a Mihon extension called "DeviantArt" in Kotlin, inside a fork of
keiyoushi/extensions-source. Read PRD.md (v2.0) fully first and follow it phase by phase.

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
