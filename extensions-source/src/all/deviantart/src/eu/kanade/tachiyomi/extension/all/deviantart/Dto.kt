package eu.kanade.tachiyomi.extension.all.deviantart

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class OEmbedDto(
    @SerialName("author_name") val authorName: String? = null,
    val title: String? = null,
    val url: String? = null,
    @SerialName("thumbnail_url") val thumbnailUrl: String? = null,
)

data class ArtistProfile(
    val username: String,
    val totalDeviations: Int = 0,
    val avatarUrl: String? = null,
    val description: String? = null,
    val folderName: String? = null,
)

@Serializable
data class CachedItem(
    val id: String,
    val title: String,
    val path: String,
    val publishedMs: Long,
)

@Serializable
data class GalleryCacheData(
    val schemaVersion: Int = 1,
    val complete: Boolean,
    val items: List<CachedItem>,
)

@Serializable
data class LedgerEntry(
    val path: String,
    val title: String,
    val publishedMs: Long,
)

@Serializable
data class ChapterLedgerData(
    val schemaVersion: Int = 1,
    val entries: MutableMap<String, LedgerEntry> = mutableMapOf(),
)

data class ImageDimension(val width: Int, val height: Int)

data class CandidateRendition(
    val name: String,
    val width: Int,
    val height: Int,
    val url: String,
    val isDownload: Boolean = false,
)

enum class QualityMode {
    BEST_AVAILABLE,
    STANDARD,
}

data class RssPageResult(
    val items: List<CachedItem>,
    val nextUrl: String? = null,
    val hasNextPage: Boolean = false,
)
