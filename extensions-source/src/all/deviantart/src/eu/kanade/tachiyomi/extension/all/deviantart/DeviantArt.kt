package eu.kanade.tachiyomi.extension.all.deviantart

import android.content.SharedPreferences
import android.widget.Toast
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import keiyoushi.annotation.Source
import keiyoushi.network.rateLimit
import keiyoushi.source.KeiSource
import keiyoushi.utils.getPreferencesLazy
import kotlinx.serialization.json.JsonElement
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import kotlin.time.Duration.Companion.seconds

@Source
abstract class DeviantArt :
    KeiSource(),
    ConfigurableSource {

    override val supportsLatest get() = true

    private val preferences: SharedPreferences by getPreferencesLazy()

    private val api by lazy { DeviantArtApi(client, preferences.debugLog) }
    private val feeds by lazy { Feeds(client, baseUrl, api) }

    override fun OkHttpClient.Builder.configureClient(): OkHttpClient.Builder = addInterceptor(
        DescriptionInterceptor(
            clientProvider = { client },
            fetchFallback = { devId -> api.fetchArtworkDescriptionById(devId) },
            maxPagesProvider = { preferences.descriptionMaxPages },
        ),
    )
        .rateLimit(permits = 2, period = 1.seconds) { it.host == "backend.deviantart.com" }
        .rateLimit(permits = 2, period = 1.seconds) { it.host == "www.deviantart.com" }
        .rateLimit(permits = 10, period = 1.seconds) { it.host.endsWith("wixmp.com") }

    override fun Headers.Builder.configureHeaders(): Headers.Builder = set("Referer", "$baseUrl/")

    // ============================== Browse (Popular & Latest) ==================

    override suspend fun getPopularManga(page: Int): MangasPage = feeds.popular(page)

    override suspend fun getLatestUpdates(page: Int): MangasPage = feeds.latest(page)

    // ============================== Search & URL Resolution ==================

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        if (url.encodedPath.contains("/art/")) {
            val details = api.fetchArtworkDetails(url.encodedPath)
            return SManga.create().apply {
                this.url = url.encodedPath
                this.title = details.title
                this.author = details.author
                this.artist = details.author
                this.description = details.description
                this.thumbnail_url = details.thumbnailUrl
                this.status = SManga.COMPLETED
                this.genre = "Artwork"
                this.initialized = true
            }
        }
        val resolved = DeviantArtUrl.resolve(url.toString())
        return resolveToMangas(resolved).firstOrNull()
    }

    /**
     * KeiSource.getSearchManga() sends ANY pasted URL here, never to getSearchMangaList(). The default
     * implementation calls getMangaByUrl(), which can return only ONE entry, so an artwork link used
     * to produce only the artwork. Return the gallery first, then the artwork.
     */
    override suspend fun getMangasByUrl(url: HttpUrl, page: Int): MangasPage {
        if (page > 1) return MangasPage(emptyList(), false)
        val resolved = DeviantArtUrl.resolve(url.toString())
        if (resolved is Resolved.Invalid) return MangasPage(emptyList(), false)
        val mangas = resolveToMangas(resolved).sortedBy { if (it.url.contains("/art/")) 1 else 0 }
        return MangasPage(mangas, false)
    }

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val trimmedQuery = query.trim()

        val feedFilter = filters.filterIsInstance<FeedFilter>().firstOrNull()
        val sortFilter = filters.filterIsInstance<SortFilter>().firstOrNull()
        val isSortNewest = sortFilter?.state == 1

        // 1. Followed feed trigger (FR-7)
        if (trimmedQuery.equals("feed:followed", ignoreCase = true) || feedFilter?.state == 2) {
            return feeds.followed(page)
        }

        // 2. Empty query: return selected feed (Popular or Latest)
        if (trimmedQuery.isEmpty()) {
            return when (feedFilter?.state) {
                1 -> feeds.latest(page)
                else -> feeds.popular(page)
            }
        }

        // 3. Link or username resolution (FR-2 & FR-15)
        val resolved = DeviantArtUrl.resolve(trimmedQuery)
        if (resolved !is Resolved.Invalid) {
            if (page > 1) return MangasPage(emptyList(), false)
            val mangas = resolveToMangas(resolved)
            if (mangas.isNotEmpty()) {
                return MangasPage(mangas, false)
            }
        }

        // 4. Keyword search on DeviantArt
        return feeds.search(trimmedQuery, page, sortByNewest = isSortNewest)
    }

    private fun resolveToMangas(resolved: Resolved): List<SManga> = when (resolved) {
        is Resolved.ArtworkAndGallery -> {
            val details = api.fetchArtworkDetails(resolved.artworkPath)
            val artworkManga = SManga.create().apply {
                url = resolved.artworkPath
                title = details.title
                author = details.author.ifBlank { resolved.ref.user }
                artist = details.author.ifBlank { resolved.ref.user }
                description = details.description
                thumbnail_url = details.thumbnailUrl
                status = SManga.COMPLETED
                genre = "Artwork"
                initialized = true
            }

            val galleryManga = createGalleryManga(resolved.ref)
            listOf(artworkManga, galleryManga)
        }
        is Resolved.Gallery -> {
            listOf(createGalleryManga(resolved.ref))
        }
        is Resolved.NeedsOembed -> {
            val dto = api.fetchOembedDto(resolved.url)
            val author = dto?.authorName ?: api.resolveOembed(resolved.url)
            if (author != null) {
                val ref = GalleryRef(author, null)
                val galleryManga = createGalleryManga(ref)

                val artworkManga = if (dto != null) {
                    val rawUrl = dto.url ?: resolved.url
                    val path = if (rawUrl.startsWith("http")) {
                        rawUrl.removePrefix("https://www.deviantart.com").removePrefix("http://www.deviantart.com")
                    } else {
                        rawUrl
                    }
                    SManga.create().apply {
                        url = path
                        title = dto.title ?: "Artwork"
                        this.author = author
                        this.artist = author
                        thumbnail_url = dto.thumbnailUrl ?: dto.url
                        status = SManga.COMPLETED
                        genre = "Artwork"
                        initialized = true
                    }
                } else {
                    null
                }
                listOfNotNull(artworkManga, galleryManga)
            } else {
                emptyList()
            }
        }
        is Resolved.Invalid -> emptyList()
    }

    private fun createGalleryManga(ref: GalleryRef): SManga = try {
        val profile = api.fetchArtistProfile(ref)
        createMangaFromProfile(profile, ref)
    } catch (_: Exception) {
        val title = if (ref.isFavourites) {
            if (ref.folderId != null) "${ref.user} – Favourite Folder ${ref.folderId}" else "${ref.user} – Favourites"
        } else {
            if (ref.folderId != null) "${ref.user} – Folder ${ref.folderId}" else "${ref.user} – Gallery"
        }
        SManga.create().apply {
            url = entryUrl(ref)
            this.title = title
            this.author = ref.user
            this.artist = ref.user
            this.status = SManga.ONGOING
            this.genre = if (ref.isFavourites) "Favourites" else "Gallery"
            this.initialized = false
        }
    }

    private fun createMangaFromProfile(profile: ArtistProfile, ref: GalleryRef): SManga = SManga.create().apply {
        url = entryUrl(ref)
        title = if (profile.folderName != null) {
            "${profile.username} – ${profile.folderName}"
        } else if (ref.isFavourites) {
            "${profile.username} – Favourites"
        } else {
            profile.username
        }
        author = profile.username
        artist = profile.username
        description = profile.description
        thumbnail_url = profile.coverUrl ?: profile.avatarUrl
        status = SManga.ONGOING
        genre = if (ref.isFavourites) "Favourites" else "Gallery"
        initialized = true
    }

    // ============================== Details + Chapters =======================

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        if (manga.url.contains("/art/")) {
            var updatedManga = manga
            var updatedChapters = chapters

            if (fetchDetails) {
                val details = api.fetchArtworkDetails(manga.url)
                updatedManga = manga.apply {
                    title = details.title.ifBlank { manga.title }
                    author = details.author.ifBlank { manga.author }
                    artist = details.author.ifBlank { manga.artist }
                    description = details.description
                    thumbnail_url = details.thumbnailUrl ?: manga.thumbnail_url
                    status = SManga.COMPLETED
                    genre = "Artwork"
                    initialized = true
                }
            }

            if (fetchChapters) {
                updatedChapters = listOf(
                    SChapter.create().apply {
                        url = manga.url
                        name = updatedManga.title.ifBlank { "Artwork" }
                        chapter_number = 1f
                        date_upload = 0L
                    },
                )
            }

            return SMangaUpdate(updatedManga, updatedChapters)
        }

        val ref = normalizeEntryUrl(manga.url)
            ?: throw IOException("Unrecognised library entry URL: ${manga.url}. Open the entry and use Migrate, or add it again.")

        var updatedManga = manga
        var updatedChapters = chapters

        if (fetchDetails) {
            val profile = api.fetchArtistProfile(ref)
            updatedManga = manga.apply {
                title = if (profile.folderName != null) {
                    "${profile.username} – ${profile.folderName}"
                } else if (ref.isFavourites) {
                    "${profile.username} – Favourites"
                } else {
                    profile.username
                }
                author = profile.username
                artist = profile.username
                description = profile.description
                thumbnail_url = profile.coverUrl ?: profile.avatarUrl ?: manga.thumbnail_url
                status = SManga.ONGOING
                genre = if (ref.isFavourites) "Favourites" else "Gallery"
                initialized = true
            }
        }

        if (fetchChapters) {
            if (preferences.chapterMode == ChapterMode.BATCHES) {
                // Appendix A: Batches mode
                val total = api.getCachedTotal(ref) ?: api.fetchArtistProfile(ref).totalDeviations
                val batchSize = 48
                val count = Batching.chapterCount(total, batchSize)
                val chapterList = mutableListOf<SChapter>()
                for (i in (count - 1) downTo 0) {
                    chapterList.add(
                        SChapter.create().apply {
                            val folderSuffix = if (ref.isAll) "all" else ref.folderId
                            val section = if (ref.isFavourites) "favourites" else "gallery"
                            url = "/${ref.user}/$section/$folderSuffix?batch=$i&size=$batchSize"
                            name = Batching.chapterName(i, batchSize, total)
                            chapter_number = (i + 1).toFloat()
                            date_upload = 0L
                        },
                    )
                }
                updatedChapters = chapterList
            } else {
                // Default: One chapter per artwork via GalleryCache crawl, disk cache, and ChapterLedger (FR-4, FR-13, FR-14)
                var useRss = false
                val items = GalleryCache.getOrCrawlGallery(
                    user = ref.user,
                    folderId = ref.folderId,
                    isFavourites = ref.isFavourites,
                    debugLog = preferences.debugLog,
                ) { offset ->
                    if (useRss) {
                        api.fetchRssPageResult(ref.user, ref.folderId, ref.isFavourites, offset)
                    } else {
                        try {
                            api.fetchGalleryJson(ref.user, ref.folderId, ref.isFavourites, offset, limit = 60)
                        } catch (e: Exception) {
                            useRss = true
                            api.fetchRssPageResult(ref.user, ref.folderId, ref.isFavourites, offset)
                        }
                    }
                }

                updatedChapters = mergeWithExisting(items, chapters, sortById = !ref.isFavourites)
            }
        }

        return SMangaUpdate(updatedManga, updatedChapters)
    }

    // ============================== Pages =====================================

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val chapterUrl = chapter.url

        if (chapterUrl.contains("batch=")) {
            // Appendix A batch mode
            val parsed = Batching.parseChapterUrl(chapterUrl)
                ?: throw IOException("Invalid chapter URL: $chapterUrl")
            val ref = normalizeEntryUrl(chapterUrl)
                ?: throw IOException("Invalid entry URL: $chapterUrl")
            val total = api.getCachedTotal(ref) ?: api.fetchArtistProfile(ref).totalDeviations
            val window = Batching.windowFor(parsed.first, parsed.second, total)
            val items = api.fetchRssPage(ref.user, ref.folderId, window.newestOffset)
            if (items.isEmpty()) throw IOException("No artworks in this batch")
            return items.take(window.count).asReversed().mapIndexed { idx, item ->
                Page(idx, url = item.path, imageUrl = api.resolveOembedImage("$baseUrl${item.path}"))
            }
        }

        // Default mode: One chapter per artwork. Fetch single deviation fresh with ImageSelector quality ranking (FR-5, FR-6, FR-16).
        val images = try {
            api.fetchDeviationImages(chapterUrl, preferences.imageQuality)
        } catch (e: Exception) {
            if (preferences.showDescription && (e.message.orEmpty().contains("literature", ignoreCase = true) || e.message.orEmpty().contains("text", ignoreCase = true))) {
                emptyList()
            } else {
                throw e
            }
        }
        if (images.isEmpty()) {
            throw IOException("This artwork is not available without login")
        }

        val pages = images.mapIndexed { index, imgUrl ->
            Page(index, url = chapterUrl, imageUrl = imgUrl)
        }.toMutableList()

        val devId = trailingIdRegex.find(chapterUrl)?.groupValues?.get(1)
            ?: chapterUrl.substringAfterLast("-").substringAfterLast("/")

        if (preferences.showDescription && devId.isNotBlank()) {
            val desc = DescriptionStore.get(devId) ?: api.fetchArtworkDescription(chapterUrl)
            if (desc != null && desc.text.isNotBlank()) {
                val maxPages = preferences.descriptionMaxPages
                val pageCount = DescriptionRenderer.computePageCount(desc, maxPages)
                if (pageCount > 0) {
                    val hash = computeDescriptionHash(desc.text, desc.title, desc.author)
                    val baseIndex = pages.size
                    for (n in 1..pageCount) {
                        pages.add(
                            Page(
                                index = baseIndex + n - 1,
                                url = chapterUrl,
                                imageUrl = "https://da-description.invalid/$devId/$hash/$n.png",
                            ),
                        )
                    }
                }
            }
        }

        return pages
    }

    override fun imageRequest(page: Page): Request = super.imageRequest(page).newBuilder()
        .header("Referer", "$baseUrl/")
        .build()

    // ============================== Filters ====================================

    override fun getFilterList(data: JsonElement?): FilterList = FilterList(
        Filter.Header("Paste a DeviantArt link to open a gallery, or search keywords"),
        Filter.Separator(),
        FeedFilter(),
        SortFilter(),
    )

    private class FeedFilter :
        Filter.Select<String>(
            "Feed",
            arrayOf("Popular", "Latest", "Latest from followed Deviants"),
        )

    private class SortFilter :
        Filter.Select<String>(
            "Sort",
            arrayOf("Popular", "Newest"),
        )

    // ============================== Settings ===================================

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val chapterModePref = ListPreference(screen.context).apply {
            key = PREF_CHAPTER_MODE_KEY
            title = "Chapter generation mode"
            entries = arrayOf("Each artwork (Default)", "Batches (Appendix A)")
            entryValues = arrayOf(ChapterMode.EACH_ARTWORK.name, ChapterMode.BATCHES.name)
            summary = "Current: %s\n\n" +
                "'Each artwork' lists every artwork as a separate chapter. " +
                "'Batches' merges artworks into batches to save memory on 20k+ galleries."
            setDefaultValue(ChapterMode.EACH_ARTWORK.name)
        }
        screen.addPreference(chapterModePref)

        val imageQualityPref = ListPreference(screen.context).apply {
            key = PREF_IMAGE_QUALITY_KEY
            title = "Image quality"
            entries = arrayOf("Best available (Default)", "Standard (full view)")
            entryValues = arrayOf(QualityMode.BEST_AVAILABLE.name, QualityMode.STANDARD.name)
            summary = "Current: %s\n\n" +
                "'Best available' downloads the true full-resolution original when allowed by the artist. " +
                "'Standard' delivers standard web full-view renditions."
            setDefaultValue(QualityMode.BEST_AVAILABLE.name)
        }
        screen.addPreference(imageQualityPref)

        val debugLogPref = SwitchPreferenceCompat(screen.context).apply {
            key = PREF_DEBUG_LOG_KEY
            title = "Enable diagnostic debug logging"
            summary = "Logs redacted network request and response status info in Android Logcat."
            setDefaultValue(false)
        }
        screen.addPreference(debugLogPref)

        val showDescriptionPref = SwitchPreferenceCompat(screen.context).apply {
            key = PREF_SHOW_DESCRIPTION_KEY
            title = "Show artwork description as page"
            summary = "Renders the artist's description and title as extra page(s) at the end of each artwork chapter."
            setDefaultValue(true)
        }
        screen.addPreference(showDescriptionPref)

        val descriptionMaxPagesPref = ListPreference(screen.context).apply {
            key = PREF_DESCRIPTION_MAX_PAGES_KEY
            title = "Maximum description pages"
            entries = arrayOf("1 page", "2 pages", "3 pages (Default)", "4 pages", "5 pages", "6 pages")
            entryValues = arrayOf("1", "2", "3", "4", "5", "6")
            summary = "Current: %s"
            setDefaultValue("3")
        }
        screen.addPreference(descriptionMaxPagesPref)

        val clearCachePref = Preference().apply {
            title = "Clear gallery disk cache"
            summary = "Deletes crawl cache files to force fresh crawling. Never deletes chapter read history or the permanent ledger."
            setOnPreferenceClickListener {
                val ok = GalleryCache.clearCache()
                val msg = if (ok) "Gallery crawl cache cleared." else "Failed to clear cache."
                Toast.makeText(screen.context, msg, Toast.LENGTH_SHORT).show()
                true
            }
        }
        screen.addPreference(clearCachePref)
    }

    private enum class ChapterMode {
        EACH_ARTWORK,
        BATCHES,
    }

    private val SharedPreferences.chapterMode: ChapterMode
        get() {
            val raw = getString(PREF_CHAPTER_MODE_KEY, ChapterMode.EACH_ARTWORK.name)
            return try {
                ChapterMode.valueOf(raw!!)
            } catch (_: Exception) {
                ChapterMode.EACH_ARTWORK
            }
        }

    private val SharedPreferences.imageQuality: QualityMode
        get() {
            val raw = getString(PREF_IMAGE_QUALITY_KEY, QualityMode.BEST_AVAILABLE.name)
            return try {
                QualityMode.valueOf(raw!!)
            } catch (_: Exception) {
                QualityMode.BEST_AVAILABLE
            }
        }

    private val SharedPreferences.debugLog: Boolean
        get() = getBoolean(PREF_DEBUG_LOG_KEY, false)

    private val SharedPreferences.showDescription: Boolean
        get() = getBoolean(PREF_SHOW_DESCRIPTION_KEY, true)

    private val SharedPreferences.descriptionMaxPages: Int
        get() = getString(PREF_DESCRIPTION_MAX_PAGES_KEY, "3")?.toIntOrNull()?.coerceIn(1, 6) ?: 3

    companion object {
        private const val PREF_CHAPTER_MODE_KEY = "chapter_mode"
        private const val PREF_IMAGE_QUALITY_KEY = "image_quality"
        private const val PREF_DEBUG_LOG_KEY = "debug_log"
        private const val PREF_SHOW_DESCRIPTION_KEY = "show_description"
        private const val PREF_DESCRIPTION_MAX_PAGES_KEY = "description_max_pages"
    }
}

// ============================== Chapter merge ==============================

internal val trailingIdRegex = Regex("""-(\d+)(?:[/?#].*)?$""")

internal fun artworkId(url: String): Long = trailingIdRegex.find(url)?.groupValues?.get(1)?.toLongOrNull() ?: 0L

internal fun chapterKey(url: String): String = artworkId(url).takeIf { it != 0L }?.toString() ?: url

/**
 * Mihon deletes every stored chapter that is missing from the list we return, together with its
 * read history. The result must therefore ALWAYS be a superset of `existing` (the chapters Mihon
 * already stores). Existing chapters keep their stored URL (their identity); crawled items only
 * add what is new. A partial or failed crawl can no longer shrink a gallery.
 */
internal fun mergeWithExisting(
    crawled: List<CachedItem>,
    existing: List<SChapter>,
    sortById: Boolean,
    chapterFactory: () -> SChapter = { SChapter.create() },
): List<SChapter> {
    val byKey = LinkedHashMap<String, SChapter>()
    val crawledOrder = ArrayList<String>()

    for (item in crawled) {
        val key = chapterKey(item.path)
        crawledOrder.add(key)
        byKey[key] = chapterFactory().apply {
            url = item.path
            name = item.title
            date_upload = item.publishedMs
        }
    }
    for (old in existing) {
        if (old.url.contains("batch=")) continue // chapters from the optional batch mode
        val key = chapterKey(old.url)
        // keep the STORED url so Mihon keeps matching the same chapter (and its read state)
        byKey[key] = chapterFactory().apply {
            url = old.url
            name = byKey[key]?.name ?: old.name
            date_upload = byKey[key]?.date_upload?.takeIf { it > 0L } ?: old.date_upload
        }
    }

    val ordered: List<SChapter> = if (sortById) {
        byKey.values.sortedWith(
            compareByDescending<SChapter> { artworkId(it.url) }.thenByDescending { it.date_upload },
        )
    } else {
        val inCrawl = crawledOrder.mapNotNull { byKey[it] }
        val crawlKeys = crawledOrder.toHashSet()
        inCrawl + byKey.filterKeys { it !in crawlKeys }.values
    }

    val total = ordered.size
    ordered.forEachIndexed { index, chapter ->
        chapter.chapter_number = (total - index).toFloat()
    }
    return ordered
}
