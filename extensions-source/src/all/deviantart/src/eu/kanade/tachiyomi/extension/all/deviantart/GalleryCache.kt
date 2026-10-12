package eu.kanade.tachiyomi.extension.all.deviantart

import android.app.Application
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

object GalleryCache {

    private const val MAX_CRAWL_DURATION_MS = 4 * 60 * 1000L // 4 minute time budget per refresh

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        prettyPrint = false
    }

    private val mutexes = ConcurrentHashMap<String, Mutex>()

    var customStorageDir: File? = null

    val storageDir: File
        get() {
            customStorageDir?.let { return it }
            return try {
                Injekt.get<Application>().filesDir.resolve("deviantart").apply { mkdirs() }
            } catch (_: Throwable) {
                File(System.getProperty("java.io.tmpdir"), "deviantart_cache").apply { mkdirs() }
            }
        }

    fun cacheFile(user: String, folderId: String?): File {
        val folder = folderId?.takeIf { !it.equals("all", ignoreCase = true) } ?: "all"
        val slug = "${user.lowercase()}_$folder.json"
        return storageDir.resolve(slug)
    }

    /**
     * Deletes crawl cache files to force a fresh crawl on next refresh.
     * NEVER deletes chapter ledger files (FR-13 item 7).
     */
    fun clearCache(): Boolean = try {
        val files = storageDir.listFiles() ?: emptyArray()
        var allOk = true
        for (f in files) {
            // Only delete crawl cache files, preserving ledger_*.json and *.bad
            if (f.isFile && !f.name.startsWith("ledger_") && f.name.endsWith(".json")) {
                if (!f.delete()) allOk = false
            }
        }
        allOk
    } catch (_: Exception) {
        false
    }

    /**
     * Pure function: `page` is newest-first.
     * Returns fresh unseen items (newest-first) and whether a known id was reached.
     */
    fun newItemsFromPage(known: Set<String>, page: List<CachedItem>): Pair<List<CachedItem>, Boolean> {
        val fresh = ArrayList<CachedItem>()
        for (item in page) {
            if (item.id in known) return fresh to true
            fresh += item
        }
        return fresh to false
    }

    /**
     * Pure function: chapter_number counted from the oldest, 1-based, for a newest-first list.
     */
    fun chapterNumber(indexNewestFirst: Int, total: Int): Float = (total - indexNewestFirst).toFloat()

    fun loadCache(user: String, folderId: String?): GalleryCacheData? {
        val file = cacheFile(user, folderId)
        if (!file.exists()) return null
        return try {
            json.decodeFromString<GalleryCacheData>(file.readText())
        } catch (_: Exception) {
            file.delete()
            null
        }
    }

    fun saveCache(user: String, folderId: String?, data: GalleryCacheData) {
        val file = cacheFile(user, folderId)
        try {
            val tmp = File.createTempFile("cache_", ".tmp", storageDir)
            tmp.writeText(json.encodeToString(data))
            if (!tmp.renameTo(file)) {
                file.delete()
                tmp.renameTo(file)
            }
        } catch (_: IOException) {
            // Non-critical write error
        }
    }

    /**
     * Loads chapters using persistent disk cache with incremental refresh, resumable crawling,
     * time budgeting (4 min), and permanent chapter ledger reconciliation.
     */
    suspend fun getOrCrawlGallery(
        user: String,
        folderId: String?,
        debugLog: Boolean = false,
        fetchPageResult: suspend (offset: Int) -> RssPageResult,
    ): List<CachedItem> {
        val folderKey = folderId?.takeIf { !it.equals("all", ignoreCase = true) } ?: "all"
        val key = "${user.lowercase()}_$folderKey"
        val mutex = mutexes.getOrPut(key) { Mutex() }

        return mutex.withLock {
            val cached = loadCache(user, folderId)
            val validCached = cached?.takeIf { it.schemaVersion >= 2 }

            if (validCached != null && validCached.complete && validCached.items.isNotEmpty()) {
                // Incremental refresh: fetch page by page from offset 0 until a known id is found
                val knownIds = validCached.items.map { it.id }.toSet()
                val newItems = mutableListOf<CachedItem>()
                var currentOffset = 0
                val startTime = System.currentTimeMillis()

                while (true) {
                    val pageResult = fetchPageResult(currentOffset)
                    val page = pageResult.items
                    if (page.isEmpty()) break

                    val (fresh, foundKnown) = newItemsFromPage(knownIds, page)
                    newItems.addAll(fresh)

                    if (foundKnown || !pageResult.hasNextPage) break

                    val nextOffsetFromLink = pageResult.nextUrl?.let { link ->
                        try {
                            link.toHttpUrl().queryParameter("offset")?.toIntOrNull()
                        } catch (_: Exception) {
                            null
                        }
                    }
                    currentOffset = nextOffsetFromLink ?: (currentOffset + page.size)

                    if (System.currentTimeMillis() - startTime > MAX_CRAWL_DURATION_MS) {
                        break
                    }
                }

                val finalItems = if (newItems.isNotEmpty()) {
                    val combined = newItems + validCached.items
                    saveCache(user, folderId, GalleryCacheData(schemaVersion = 2, complete = true, items = combined))
                    combined
                } else {
                    validCached.items
                }

                // Enforce ledger superset invariant and preserve first-seen paths
                return@withLock ChapterLedger.recordChapters(user, folderId, finalItems, debugLog)
            }

            // Full crawl or resume partial crawl
            val partialItems = validCached?.items?.toMutableList() ?: mutableListOf()
            val seenIds = partialItems.map { it.id }.toMutableSet()
            var offset = partialItems.size
            var pagesSinceSave = 0
            val crawlStartTime = System.currentTimeMillis()

            while (true) {
                // Check 4-minute time budget (FR-12 item 7)
                if (System.currentTimeMillis() - crawlStartTime > MAX_CRAWL_DURATION_MS) {
                    saveCache(user, folderId, GalleryCacheData(schemaVersion = 2, complete = false, items = partialItems))
                    throw IOException(
                        "Loading gallery: ${partialItems.size} artworks cached so far. Update again to continue.",
                    )
                }

                val pageResult = fetchPageResult(offset)
                val page = pageResult.items
                if (page.isEmpty()) break

                for (item in page) {
                    if (seenIds.add(item.id)) {
                        partialItems.add(item)
                    }
                }

                pagesSinceSave++
                if (pagesSinceSave >= 20) {
                    saveCache(user, folderId, GalleryCacheData(schemaVersion = 2, complete = false, items = partialItems))
                    pagesSinceSave = 0
                }

                if (!pageResult.hasNextPage) {
                    // Reached end of gallery
                    break
                }

                val nextOffsetFromLink = pageResult.nextUrl?.let { link ->
                    try {
                        link.toHttpUrl().queryParameter("offset")?.toIntOrNull()
                    } catch (_: Exception) {
                        null
                    }
                }
                offset = nextOffsetFromLink ?: (offset + page.size)
            }

            saveCache(user, folderId, GalleryCacheData(schemaVersion = 2, complete = true, items = partialItems))
            // Reconcile with permanent ChapterLedger
            ChapterLedger.recordChapters(user, folderId, partialItems, debugLog)
        }
    }

    suspend fun getOrCrawlGalleryWithItems(
        user: String,
        folderId: String?,
        debugLog: Boolean = false,
        fetchPageItems: suspend (offset: Int) -> List<CachedItem>,
    ): List<CachedItem> = getOrCrawlGallery(user, folderId, debugLog) { offset ->
        val items = fetchPageItems(offset)
        RssPageResult(items, null, items.size >= 60)
    }
}
