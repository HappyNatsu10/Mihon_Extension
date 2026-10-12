# PRD: DeviantArt Source Extension for Mihon

**Doc version:** 1.0  |  **Target builder:** Google Antigravity (agentic IDE)  |  **Language:** Kotlin  |  **Output:** Installable Mihon extension APK

---

## 0. How to use this document with Antigravity

1. Fork/clone the **keiyoushi/extensions-source** repository (the standard template for Mihon/Tachiyomi extensions). Build inside that repo, not from scratch.
2. Save this file in the repo root as `PRD.md`.
3. Optionally copy **Section 14 (Agent Rules)** into your Antigravity workspace rules so they apply to every task.
4. Paste the **Kickoff Prompt (Section 15)** into the Antigravity Agent Manager.
5. The agent must work **phase by phase (Section 11)** and stop at each phase's acceptance criteria. Phase 0 (API discovery) is mandatory before any extension code is written, because several endpoint details in this document are unverified and must be confirmed against the live site.

> **Important convention in this doc:** anything marked **[VERIFY]** is a best-guess that the agent must confirm during Phase 0 using the Antigravity browser/network inspection and `curl`. If reality differs, reality wins; record the difference in `docs/API_FINDINGS.md`.

---

## 1. Overview

### 1.1 Problem
Mihon is a manga reader built around "entries" with "chapters" of image pages. DeviantArt (DA) has no Mihon source. The user wants to follow DA artists inside Mihon by pasting a DA link.

### 1.2 Product goal
A Mihon extension named **DeviantArt** where:
- Pasting a DeviantArt **artwork link** (or profile/gallery link) into Mihon's **Search** bar shows that **artist's profile gallery** as a library-addable entry.
- Adding the entry to the library works for **very large galleries (20,000+ deviations)** without freezing, crashing, timing out, or getting rate-banned.
- Opening a "chapter" shows a batch of the artist's artworks as pages.

### 1.3 Non-goals (v1)
- Logging in inside the extension / storing DA credentials.
- Favourites, Scraps, Journals, Literature (text) rendering, comments, watchlist, or search-by-keyword/tag.
- Downloading paywalled/premium/"download-protected" originals or bypassing any access control.
- Publishing to Google Play. (Mihon extensions are distributed as APKs/repos.)

---

## 2. User stories

| # | Story | Priority |
|---|-------|----------|
| U1 | As a reader, I paste `https://www.deviantart.com/<artist>/art/<title>-<id>` into Browse → Search and see the artist's gallery entry. | P0 |
| U2 | I paste a profile link (`deviantart.com/<artist>`) or gallery link (`/<artist>/gallery`) and get the same entry. | P0 |
| U3 | I add an artist with 20k+ gallery items to my library; it adds and updates quickly. | P0 |
| U4 | I open the entry and see chapters (batches of artworks) in a sensible order; reading a chapter shows artworks as full-size pages. | P0 |
| U5 | When the artist uploads new art, library update shows a new chapter / new pages. | P1 |
| U6 | I can change batch size in extension settings. | P1 |
| U7 | I can browse a "Popular" list for discovery (authors of today's Daily Deviations). | P2 |
| U8 | I can filter by a gallery folder. | P2 (stretch) |
| U9 | Tapping a deviantart.com link in another app can open it in Mihon. | P3 (stretch) |

---

## 3. Concept mapping (DeviantArt → Mihon)

| DeviantArt | Mihon | Notes |
|------------|-------|-------|
| An artist (user/group) | `SManga` | `url = "/<username>"` (relative, no domain). `title = username`. |
| Artist avatar | `thumbnail_url` | |
| Artist bio/tagline + counts | `description` | |
| A **batch** of N consecutive gallery deviations | `SChapter` | **Not one chapter per artwork** (see Section 5). |
| One deviation (image) | `Page` | Non-image deviations are skipped. |

---

## 4. Functional requirements

### FR-1 Source definition
- `HttpSource` (+ `ConfigurableSource` for preferences), `lang = "all"`, `name = "DeviantArt"`, `baseUrl = "https://www.deviantart.com"`, `supportsLatest = false` (v1).
- Package: `eu.kanade.tachiyomi.extension.all.deviantart`. Module path: `src/all/deviantart/`.

### FR-2 Link-in-Search resolution (core feature)
Override the search entry point (`fetchSearchManga` or `getSearchManga`, whichever the template's extensions-lib version exposes) so that **before** doing any normal search it tries to interpret `query`.

**Accepted inputs → resolved username:**

| Input | Resolution |
|-------|------------|
| `https://www.deviantart.com/{user}` | username = `{user}` |
| `https://www.deviantart.com/{user}/gallery` and `/gallery/{folderId}/{slug}` | username = `{user}` (folder ignored in v1) |
| `https://www.deviantart.com/{user}/art/{slug}-{id}` (**artwork link, the main case**) | username = path segment 1. **No network call needed.** |
| `https://{user}.deviantart.com/...` (legacy subdomain, `user != www`) | username = subdomain |
| `https://www.deviantart.com/deviation/{id}`, `https://www.deviantart.com/art/{slug}-{id}` (no user in path), `https://fav.me/d{base36}` | Resolve via DA oEmbed: `GET https://backend.deviantart.com/oembed?url={urlEncoded}` → `author_name` **[VERIFY]** |
| `user:{name}` or `@{name}` | username = `{name}` |
| Bare text matching username regex, no spaces | Treat as a username candidate and validate (FR-3) |

Rules:
- Reserved first path segments (`tag`, `search`, `topic`, `topics`, `watch`, `shop`, `join`, `about`, `developers`, `checkout`, `settings`, `messages`, `notifications`, `daily-deviations`, `popular`, `newest`, `core-membership`, `studio`, `users`, `today`, `deviation`, `art`, `journal`) are **not** usernames. `art` and `deviation` route to the oEmbed path.
- Username regex: `^[A-Za-z0-9][A-Za-z0-9-]{1,}$`. Usernames are case-insensitive; store the canonical casing returned by DA.
- Result: a `MangasPage` with exactly **one** `SManga` (`hasNextPage = false`), populated with title, thumbnail, and a minimal description so it can be added from the search grid.
- Unresolvable input → empty result (not a crash).

### FR-3 User validation + details (`getMangaDetails`)
- Fetch the public profile; populate: `title` (username), `author`/`artist` (username), `thumbnail_url` (avatar), `description` (tagline/bio + "Gallery: ~N artworks"), `status = ONGOING`, `genre = ["Gallery"]`, `initialized = true`.
- 404 / deactivated / banned account → throw a clear `Exception("User not found or account unavailable")`.

### FR-4 Chapter list (`getChapterList`): batching
- Build chapters **synthetically** from the total item count (Section 5); do **not** download all 20k deviations to list chapters.
- Default batch size **48** (preference: 24 / 48 / 96 / 192).
- Chapter order returned to Mihon: **newest first** (Mihon convention). Numbering: **oldest batch = chapter 1**, newest batch = highest number.
- `SChapter.url = "/{user}/gallery?batch={i}&size={B}"` (`i` is zero-based from the oldest). Encoding `size` in the URL keeps old library chapters valid if the user later changes the preference.
- `SChapter.name`: `"Artworks {from}–{to}"` using 1-based oldest-first numbering, e.g. `Artworks 49–96`.
- `chapter_number = i + 1`. `date_upload`: set for the newest chapter only if cheaply available (first item of the newest page); otherwise `0`. Do **not** make extra requests per chapter just to fill dates.
- Empty gallery → empty chapter list (not an error).

### FR-5 Page list (`getPageList`)
- Parse `batch` and `size` from the chapter URL. Re-compute total `T` (cached count, Section 5.3), then convert to API offsets (Section 5.2).
- Fetch the needed 24-item API pages sequentially (batch size 48 → 2 requests).
- Within a chapter, pages are ordered **oldest → newest** (reverse the newest-first API order).
- Skip non-image deviations (literature, journals, videos, etc.). Offsets are computed on **raw** API items so skipping never shifts chapter boundaries. A chapter may therefore have fewer pages than `size`.
- If a chapter ends up with zero image pages, throw `Exception("No image artworks in this batch")`.
- Each `Page(index, url, imageUrl)`: `imageUrl` is set directly to the best available full-view image URL **[VERIFY field names]**. `url` should carry the deviation id/URL for fallback and "open in browser".
- `imageUrlParse` → `throw UnsupportedOperationException()` if `imageUrl` is always pre-filled.

### FR-6 Image requests
- Override `imageRequest` to add `Referer: https://www.deviantart.com/` and a realistic `User-Agent`.
- Prefer the largest non-watermarked public rendition ("fullview"/original public size). Animated GIFs: use the GIF rendition if exposed **[VERIFY]**.
- Image tokens may be time-limited (JWT in a `token` query param **[VERIFY]**). Resolve them at page-list time; do not cache image URLs across sessions.

### FR-7 Browse (Popular)
- `popularMangaRequest/Parse`: unique **authors of recent Daily Deviations** (e.g., RSS `https://backend.deviantart.com/rss.xml?q=special:dd` **[VERIFY]**). Each becomes an `SManga` like FR-2. If this is unreliable, return an empty page; the primary use case is pasted links.
- Do not implement Latest in v1 (`supportsLatest = false`).

### FR-8 Preferences (`ConfigurableSource`)
| Key | Type | Default | Purpose |
|-----|------|---------|---------|
| `batch_size` | List (24/48/96/192) | 48 | Artworks per chapter |
| `skip_non_images` | Switch | true | Skip literature/journals |
| `api_client_id` / `api_client_secret` | Text | empty | Optional official-API mode (Section 6, Mode B) |

### FR-9 Mature content
- Unauthenticated requests will only return non-mature items. Mihon's HTTP client shares cookies with the app WebView, so a user who logs into DA via Mihon's **Open in WebView** gets mature content automatically. The extension must **never** ask for, store, or transmit DA credentials.
- If an API response indicates mature/age-gated content, skip the item silently and log it; do not crash.

### FR-10 (Stretch) Deep link
- `AndroidManifest.xml` intent filter for `deviantart.com` + `UrlActivity` that forwards to Mihon's `eu.kanade.tachiyomi.SEARCH` intent with the URL as `query` (follow the pattern used by other extensions in the template repo).

---

## 5. Scaling design for 20,000+ items (the hard part)

### 5.1 Why not one chapter per artwork?
20k chapters in Mihon's DB makes the entry screen, library updates, and downloads sluggish and risks OOM/timeouts. Batching 48 items per chapter turns 20,000 items into ~417 chapters.

### 5.2 Stable offsets for batches
The gallery API is paged **newest-first** by offset. We want chapter 1 = oldest so numbering is stable when new art is posted.

Let `T` = total items, `B` = batch size, `i` = zero-based batch index from the oldest.

```
oldestStart = i * B
oldestEnd   = min((i + 1) * B, T)          // exclusive
count       = oldestEnd - oldestStart
newestOffsetStart = T - oldestEnd          // API offset (newest-first)
// fetch `count` items starting at newestOffsetStart in API pages of <=24,
// then REVERSE the combined list so pages read oldest -> newest.
```
Total chapters = `ceil(T / B)`. Only the newest chapter is "growing"; completed older chapters keep identical content as long as nothing is deleted.

### 5.3 Getting `T` without downloading everything
The listing API may not return a total count. Strategy (try in order):
1. **Count field** in the API response if present **[VERIFY]**.
2. **Offset probing:** exponential search for the first empty offset (24 → 48 → 96 → … → 49,152), then binary search between the last non-empty and first empty offset. Use `limit=1` if allowed, else `limit=24`. Expect ~15–17 requests for 20k items. Cost is fixed regardless of gallery size.
3. **Fallback estimate:** profile stats (e.g., `stats.user_deviations`) **[VERIFY]** → use only if probing fails (this can over-count; clamp when pages come back empty).

Cache `T` per username in memory for ~10 minutes (small `LinkedHashMap`/LRU, max 50 users) so `getChapterList` followed by `getPageList` does not re-probe.

### 5.4 Rate-limit & memory rules
- Wrap the OkHttp client with a rate limiter (e.g., `network.cloudflareClient.newBuilder().rateLimit(permits = 2, period = 1, unit = TimeUnit.SECONDS)` for the API host and a separate, looser limit for the image CDN host; use whichever `rateLimit`/`rateLimitHost` helper the template provides).
- Handle HTTP **429**: respect `Retry-After`, otherwise exponential backoff (max 3 retries), then fail with a readable error.
- Never hold more than one chapter's worth of deviation JSON in memory. Never loop over the entire gallery.
- All network on Mihon's I/O threads; never block the main thread.

---

## 6. Data sources & API strategy

DA offers an official OAuth2 API and also has internal website JSON endpoints. Phase 0 decides which to use.

**Mode A: Unauthenticated web endpoints (preferred default; zero setup for users)** **[VERIFY all]**
- Gallery contents (candidate): `https://www.deviantart.com/_napi/da-user-profile/api/gallery/contents?username={u}&offset={o}&limit=24&all_folder=true`
  - Expected response: `hasMore`, `nextOffset`, `results[]` with a `deviation` object (`deviationId`, `title`, `type`, `isMature`, `media.baseUri`, `media.prettyName`, `media.token[]`, `media.types[]` with `t` such as `fullview`/`preview`/`gif`, optional `c` path template).
- Profile (candidate): `https://www.deviantart.com/_napi/da-user-profile/api/init/about?username={u}` or parsing the profile page.
- If these need a CSRF token / cookie bootstrap, implement it as a small interceptor that fetches `https://www.deviantart.com/{u}` once and caches what is needed.

**Mode B: Official API with user-supplied credentials (optional fallback)**
- Token: `POST https://www.deviantart.com/oauth2/token` with `grant_type=client_credentials`, `client_id`, `client_secret` → bearer token (~1 hour; cache + refresh).
- Gallery: `GET https://www.deviantart.com/api/v1/oauth2/gallery/all?username={u}&offset={o}&limit=24`; profile: `GET /api/v1/oauth2/user/profile/{u}`; folders: `GET /api/v1/oauth2/gallery/folders?username={u}`.
- Client-credentials tokens only see public, non-mature content. The user creates their own DA developer app; **never ship a shared client id/secret in the APK.**

**Mode C: RSS fallback** (documented, stable, no auth) **[VERIFY]**
- `https://backend.deviantart.com/rss.xml?type=deviation&q=gallery:{u}` (offset paging, ~60 items/page, `media:content` has the image). Evaluate offset ceiling; may not reach deep into 20k+ galleries.

**oEmbed (for non-user URLs):** `https://backend.deviantart.com/oembed?url={url}` → `author_name`.

**Decision rule (Phase 0):** choose the first mode that, **unauthenticated**, (a) pages at ≥24 items/request, (b) works at offsets ≥ 25,000, (c) exposes full-size image URLs. Record the choice and sample JSON in `docs/API_FINDINGS.md` and `fixtures/`. Implement the chosen mode as primary; implement Mode B as optional if time allows.

---

## 7. Architecture & file layout

```
src/all/deviantart/
├── build.gradle
├── AndroidManifest.xml                 # (stretch: deep link)
├── res/mipmap-*/ic_launcher.png        # simple "DA" style icon (generate, do not copy official logo)
└── src/eu/kanade/tachiyomi/extension/all/deviantart/
    ├── DeviantArt.kt          # HttpSource + ConfigurableSource
    ├── DeviantArtUrl.kt       # input -> Resolved (pure functions, unit-testable)
    ├── DeviantArtApi.kt       # endpoints, probing, paging, token/CSRF handling
    ├── Dto.kt                 # @Serializable models
    ├── Batching.kt            # batch math (pure functions, unit-testable)
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
    isNsfw = false   // revisit: set true if mature content support is enabled
}

apply from: "$rootDir/common.gradle"
```

**URL resolution skeleton (`DeviantArtUrl.kt`)**
```kotlin
sealed interface Resolved {
    data class Username(val name: String) : Resolved
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
    private val MAIN = Regex("""^https?://(?:www\.)?deviantart\.com/([^/?#]+)(?:[/?#].*)?$""", RegexOption.IGNORE_CASE)
    private val SUB = Regex("""^https?://([A-Za-z0-9-]+)\.deviantart\.com(?:[/?#].*)?$""", RegexOption.IGNORE_CASE)
    private val FAVME = Regex("""^https?://fav\.me/d[0-9a-z]+""", RegexOption.IGNORE_CASE)

    fun resolve(raw: String): Resolved {
        val q = raw.trim()
        if (q.startsWith("user:", true)) return q.substring(5).trim().asUser()
        if (q.startsWith("@")) return q.substring(1).trim().asUser()
        if (FAVME.containsMatchIn(q)) return Resolved.NeedsOembed(q)
        MAIN.find(q)?.let { m ->
            val seg = m.groupValues[1]
            return if (seg.lowercase() in RESERVED) Resolved.NeedsOembed(q) else seg.asUser()
        }
        SUB.find(q)?.let { m ->
            val sub = m.groupValues[1]
            if (!sub.equals("www", true)) return sub.asUser()
        }
        return if (USER.matches(q)) q.asUser() else Resolved.Invalid
    }

    private fun String.asUser() = if (USER.matches(this)) Resolved.Username(this) else Resolved.Invalid
}
```
> Note: `/{user}/art/{slug}-{id}` has the username as segment 1, so it resolves with **no network call**. Only `/art/...`, `/deviation/...`, and `fav.me` go through oEmbed.

**Batch math skeleton (`Batching.kt`)**
```kotlin
data class BatchWindow(val newestOffset: Int, val count: Int)

fun chapterCount(total: Int, size: Int) = if (total <= 0) 0 else (total + size - 1) / size

fun windowFor(batchIndex: Int, size: Int, total: Int): BatchWindow {
    val oldestEnd = minOf((batchIndex + 1) * size, total)
    val oldestStart = batchIndex * size
    return BatchWindow(newestOffset = total - oldestEnd, count = oldestEnd - oldestStart)
}
```

**Main class outline (`DeviantArt.kt`)** (adapt signatures to the template's extensions-lib version)
```kotlin
class DeviantArt : HttpSource(), ConfigurableSource {
    override val name = "DeviantArt"
    override val baseUrl = "https://www.deviantart.com"
    override val lang = "all"
    override val supportsLatest = false

    override val client = network.cloudflareClient.newBuilder()
        // rate limit API + CDN hosts, 429 backoff interceptor, optional auth interceptor
        .build()

    override fun headersBuilder() = super.headersBuilder()
        .set("Referer", "$baseUrl/")

    // Popular = authors of recent Daily Deviations (best effort)
    // Search  = DeviantArtUrl.resolve(query) -> single SManga (validate via API), else empty
    // Details = profile -> SManga
    // Chapters = Batching.chapterCount(T, B) synthetic list, newest first
    // Pages   = windowFor(...) -> fetch 24-item API pages -> map to Page -> reverse
    // imageRequest = add Referer
}
```
Use the template's `keiyoushi.utils` helpers (`parseAs<T>()`, `getPreferencesLazy`) where available, and `kotlinx.serialization` with `ignoreUnknownKeys = true` for all DTOs (DA JSON changes often).

---

## 8. Error handling matrix

| Situation | Behaviour |
|-----------|-----------|
| Unrecognised search text | Empty result |
| User not found / deactivated | Exception: "User not found or account unavailable" |
| Gallery empty or all items non-image | Entry loads; zero chapters or clear page-list error |
| HTTP 429 | Backoff + retry (≤3), then readable error |
| HTTP 403 / Cloudflare challenge | Error text telling the user to open the source in WebView once to pass the challenge |
| Mature item without login | Skip silently |
| JSON shape changed | Fail with "DeviantArt response format changed" (never NPE); DTO fields nullable |
| Image token expired (401/403 on image) | Mihon retry re-requests the page list; resolve fresh URLs |

---

## 9. Non-functional requirements

- **Latency:** search-by-link → result < 3 s (artwork URL, no oEmbed). Chapter list for a 20k gallery < 10 s using ≤ ~20 requests.
- **Scale:** correct for galleries of 0, 1, 47, 48, 49, 20,000, 50,000+ items (boundary tests on batch math).
- **Resilience:** all DTO fields nullable/defaulted; no `!!`.
- **Politeness:** conservative rate limit; identify with a normal browser UA; no parallel hammering.
- **Privacy:** no analytics, no telemetry, no credential handling.
- **Code quality:** pure functions for URL parsing and batching; no hard-coded secrets; passes the template repo's lint (`ktlint`/`spotless` if configured).
- **Compatibility:** latest stable Mihon; extension lib version used by the template repo.

---

## 10. Success metrics / definition of done

1. Pasting a real artwork link returns the artist entry in Browse → Search.
2. A real public account with 20k+ gallery items can be added to library; entry shows ~`ceil(T/B)` chapters; first-open of any chapter shows images in < 10 s.
3. Library update on that entry does not trigger more than ~20 requests per entry.
4. Unit tests for URL parsing and batch math pass.
5. `./gradlew :src:all:deviantart:assembleDebug` produces an APK that installs and runs in Mihon.

---

## 11. Phased execution plan (agent must stop and report after each phase)

### Phase 0: API discovery (no extension code yet)
**Tasks**
- Using the Antigravity browser + terminal, inspect DA's gallery page network calls and test Modes A/B/C from Section 6.
- Find one public account with >20,000 gallery items and one with a small gallery (<50), one with an empty gallery, plus a nonexistent username.
- Answer, with evidence: max `limit`; does offset work beyond 25,000; pagination fields; total-count availability; image URL fields + token expiry; non-image types; mature behaviour; 429 behaviour; required headers/cookies; oEmbed output.
- Save raw sample JSON to `fixtures/` and write `docs/API_FINDINGS.md` including the chosen mode and the decision rationale.

**Acceptance:** `API_FINDINGS.md` exists, every **[VERIFY]** item in this PRD is resolved (confirmed / changed / dropped), and the chosen mode is demonstrated with working `curl` commands.

### Phase 1: Scaffold
- Create the module `src/all/deviantart/` per Section 7, icon, empty source that compiles.
- **Acceptance:** `./gradlew :src:all:deviantart:assembleDebug` succeeds; APK installs in Mihon and the source appears in Browse.

### Phase 2: Link resolution + entry details
- Implement `DeviantArtUrl`, the search override, validation, and `getMangaDetails`. Add unit tests for every row of the FR-2 table plus negatives.
- **Acceptance:** pasting an artwork URL, a profile URL, `user:name`, and a `fav.me` link each yield the right single entry; invalid text yields nothing.

### Phase 3: Counting + batching + chapter list
- Implement `T` discovery (Section 5.3), cache, `Batching.kt`, and `getChapterList`. Unit tests for boundaries (T = 0, 1, B-1, B, B+1, 20,000, 50,001).
- **Acceptance:** for the 20k+ account, chapter count == `ceil(T/B)` and the full list builds within the NFR budget; request count logged ≤ ~20.

### Phase 4: Pages + images
- Implement `getPageList`, DTO mapping, image selection, `imageRequest`, skip rules, ordering (oldest → newest).
- **Acceptance:** first chapter, a middle chapter, and the last (partial) chapter of the 20k+ account all load with correct images; chapter boundaries have no duplicates/gaps (verify by comparing deviation IDs across adjacent chapters).

### Phase 5: Hardening + preferences
- Rate limiting, 429 backoff, error matrix, preferences screen (batch size etc.), optional Mode B.
- **Acceptance:** simulated 429 and 404 behave per Section 8; changing batch size yields correct chapters without breaking existing ones.

### Phase 6: Browse + polish (P2/P3)
- Popular = Daily Deviations authors; stretch: folder filter, deep link.
- **Acceptance:** Popular loads or fails gracefully; lint passes.

### Phase 7: Packaging
- Produce signed/debug APK, short `README.md` (install steps, how to paste links, mature-content WebView login tip, limitations), and, if the user wants a private extension repo, set up the template's GitHub Actions to publish `index.min.json` + APK.
- **Acceptance:** fresh install on a device/emulator works end-to-end following the README only.

---

## 12. Test plan

**Automated (JVM):** URL resolver table tests; batch-window math; DTO parsing against `fixtures/*.json`; image-URL selection logic; probing algorithm against a fake paging function (simulate T = 0, 1, 23, 24, 25, 20,000, 50,000).

**Manual (device/emulator with Mihon):**
1. Paste: artwork URL / profile URL / gallery URL / `fav.me` / `user:name` / garbage.
2. Add a 20k+ artist to the library; confirm responsiveness; open first, middle, last chapters.
3. Pull-to-refresh and library update on that entry.
4. Small-gallery and empty-gallery artists; nonexistent username.
5. Change batch size in settings; reopen entry.
6. Airplane-mode and 429 error messages are readable.
7. WebView login → mature items appear (if the account allows).

---

## 13. Risks, constraints & legal notes

- **Unofficial endpoints can change without notice.** Keep parsing isolated in `DeviantArtApi.kt`/`Dto.kt`, with nullable DTOs and clear errors, so fixes are small.
- **Terms of Service:** DA's terms restrict automated scraping, and artwork is copyrighted by its creators. This extension is for **personal viewing**; keep requests low-rate, do not circumvent paywalls, premium content, download protection, or login/age gates, and do not redistribute artwork. The developer is responsible for assessing ToS/legal risk before distributing publicly.
- **Cloudflare/anti-bot** may block requests; use `network.cloudflareClient` and document the "open in WebView" workaround.
- **Unverified assumptions** (offset ceiling, `limit` max, field names) are all gated by Phase 0.
- **Chapter semantics:** chapters are batches, not stories; reading progress is per batch. This is a deliberate trade-off for 20k+ scale.

---

## 14. Agent Rules (copy into Antigravity workspace rules)

1. Follow Sections 0 and 11 strictly: **do Phase 0 first**; do not write extension code until `docs/API_FINDINGS.md` exists.
2. Build inside the keiyoushi/extensions-source structure and match the style of existing extensions in that repo (look at 2–3 comparable `src/all/*` extensions before coding).
3. Kotlin only; use `kotlinx.serialization` with `ignoreUnknownKeys = true`; no `!!`; no blocking calls on the main thread.
4. Keep URL parsing and batch math as **pure functions** with unit tests.
5. Never embed API keys/secrets or DA credentials; never log tokens.
6. Never download or process the full gallery to list chapters; use count probing + synthetic chapters.
7. Respect rate limits (≤2 req/s to DA API, backoff on 429). Do not parallelize requests to DA.
8. Do not bypass paywalls, mature-content gates, or download protection.
9. After each phase: run build + tests, summarize results, list remaining **[VERIFY]** items, and wait for approval before the next phase.
10. If any requirement here conflicts with observed API behaviour, record it in `docs/API_FINDINGS.md`, propose the smallest change, and ask before deviating.

---

## 15. Kickoff prompt (paste into Antigravity Agent Manager)

```
You are building a Mihon extension called "DeviantArt" in Kotlin, inside a fork of
keiyoushi/extensions-source. Read PRD.md fully first. Follow it phase by phase.

Start with Phase 0 only: use the browser and terminal to discover and verify DeviantArt's
gallery/profile/oEmbed/RSS behaviour (Section 6), find a public account with >20,000 gallery
deviations, save sample JSON to fixtures/, and write docs/API_FINDINGS.md resolving every
[VERIFY] item. Do not write extension code yet.

When Phase 0 is complete, stop, present your findings and the chosen API mode, and wait for my
approval before starting Phase 1. After each later phase, run the build and tests, summarise the
results, and wait for approval.

Core requirement to keep in mind: pasting a DeviantArt artwork link into Mihon's Search bar must
show that artist's gallery as an addable entry, and it must work for galleries with 20,000+ items
using batched synthetic chapters (default 48 items/chapter) and offset-probing to find the total.
```
