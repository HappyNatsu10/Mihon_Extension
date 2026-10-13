package eu.kanade.tachiyomi.extension.all.deviantart

import android.app.Application
import android.util.Log
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.io.IOException

object ChapterLedger {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        prettyPrint = false
    }

    var customStorageDir: File? = null

    val storageDir: File
        get() {
            customStorageDir?.let { return it }
            return try {
                Injekt.get<Application>().filesDir.resolve("deviantart").apply { mkdirs() }
            } catch (_: Throwable) {
                File(System.getProperty("java.io.tmpdir"), "deviantart_ledger").apply { mkdirs() }
            }
        }

    fun ledgerFile(user: String, folderId: String?, isFavourites: Boolean = false): File {
        val folder = folderId?.takeIf { !it.equals("all", ignoreCase = true) } ?: "all"
        val prefix = if (isFavourites) "ledger_${user.lowercase()}_fav_" else "ledger_${user.lowercase()}_"
        val name = "$prefix$folder.json"
        return storageDir.resolve(name)
    }

    fun loadLedger(user: String, folderId: String?, isFavourites: Boolean = false): ChapterLedgerData {
        val file = ledgerFile(user, folderId, isFavourites)
        if (!file.exists()) return ChapterLedgerData()
        return try {
            json.decodeFromString<ChapterLedgerData>(file.readText())
        } catch (_: Exception) {
            // Corrupt ledger: rename to .bad and rebuild, never discard silently (FR-13 item 9)
            val badFile = File(file.parentFile, "${file.name}.bad")
            file.renameTo(badFile)
            ChapterLedgerData()
        }
    }

    fun saveLedger(user: String, folderId: String?, isFavourites: Boolean = false, data: ChapterLedgerData) {
        val file = ledgerFile(user, folderId, isFavourites)
        try {
            val tmp = File.createTempFile("ledger_", ".tmp", storageDir)
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
     * Applies the Chapter Ledger rules and enforces the superset invariant (FR-13 items 7-10).
     * 1. Takes the first-seen path from the ledger for any known ID (survives title/slug changes).
     * 2. Re-emits any IDs present in the ledger that are missing from the fresh crawl (superset invariant).
     * 3. Appends new IDs to the ledger.
     */
    fun recordChapters(
        user: String,
        folderId: String?,
        isFavourites: Boolean,
        crawledItems: List<CachedItem>,
        debugLog: Boolean = false,
    ): List<CachedItem> {
        val ledger = loadLedger(user, folderId, isFavourites)
        val initialLedgerCount = ledger.entries.size
        var idsAdded = 0
        var pathsPreserved = 0

        val crawledIds = HashSet<String>(crawledItems.size)
        val reconciledList = ArrayList<CachedItem>(crawledItems.size + ledger.entries.size)

        for (item in crawledItems) {
            crawledIds.add(item.id)
            val existing = ledger.entries[item.id]
            if (existing != null) {
                // Preserve first-seen path from ledger
                if (existing.path != item.path) {
                    pathsPreserved++
                    if (debugLog) {
                        Log.d("DeviantArt", "Ledger path preserved for ${item.id}: ${existing.path} (fresh: ${item.path})")
                    }
                }
                reconciledList.add(item.copy(path = existing.path))
            } else {
                // New artwork
                idsAdded++
                ledger.entries[item.id] = LedgerEntry(
                    path = item.path,
                    title = item.title,
                    publishedMs = item.publishedMs,
                )
                reconciledList.add(item)
            }
        }

        // Superset invariant: IDs in ledger but missing from fresh crawl
        var retainedCount = 0
        for ((id, entry) in ledger.entries) {
            if (!crawledIds.contains(id)) {
                retainedCount++
                reconciledList.add(
                    CachedItem(
                        id = id,
                        title = entry.title,
                        path = entry.path,
                        publishedMs = entry.publishedMs,
                    ),
                )
            }
        }

        if (idsAdded > 0) {
            saveLedger(user, folderId, isFavourites, ledger)
        }

        if (debugLog) {
            Log.d(
                "DeviantArt",
                "Ledger summary for $user/$folderId: ledgerSize=${ledger.entries.size}, " +
                    "crawlSize=${crawledItems.size}, returnedSize=${reconciledList.size}, " +
                    "added=$idsAdded, retainedFromLedger=$retainedCount, initial=$initialLedgerCount",
            )
        }

        return reconciledList
    }

    fun recordChapters(
        user: String,
        folderId: String?,
        crawledItems: List<CachedItem>,
        debugLog: Boolean = false,
    ): List<CachedItem> = recordChapters(user, folderId, false, crawledItems, debugLog)
}
