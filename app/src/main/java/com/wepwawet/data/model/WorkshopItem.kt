package com.wepwawet.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Steam Workshop item — the subset of fields we care about, parsed from the
 * ISteamRemoteStorage/GetPublishedFileDetails/v1/ API response.
 *
 * Note: the API returns `file_url` as empty for paid apps (including Wallpaper Engine),
 * so we cannot download the actual wallpaper content via this anonymous endpoint.
 * That's why we run an emulated Steam client to do the authenticated download instead.
 *
 * For browsing/searching in the UI, this is sufficient.
 */
@Serializable
data class WorkshopItem(
    @SerialName("publishedfileid") val id: String,
    @SerialName("title") val title: String,
    @SerialName("description") val description: String = "",
    @SerialName("preview_url") val previewUrl: String? = null,
    @SerialName("creator") val creator: String = "",
    @SerialName("creator_app_id") val creatorAppId: Long = 0,
    @SerialName("consumes_file_id") val consumesFileId: Long? = null,
    @SerialName("file_size") val fileSize: Long = 0,
    @SerialName("subscriptions") val subscriptions: Long = 0,
    @SerialName("favorited") val favorited: Long = 0,
    @SerialName("views") val views: Long = 0,
    @SerialName("lifetime_subscriptions") val lifetimeSubscriptions: Long = 0,
    @SerialName("lifetime_favorited") val lifetimeFavorited: Long = 0,
    @SerialName("tags") val tags: List<WorkshopTag> = emptyList(),
    @SerialName("time_created") val timeCreated: Long = 0,
    @SerialName("time_updated") val timeUpdated: Long = 0,
    @SerialName("visibility") val visibility: Int = 0,
    @SerialName("banned") val banned: Boolean = false,
    @SerialName("ban_reason") val banReason: String? = null,
    @SerialName("banner") val banner: String? = null,
    @SerialName("can_be_deleted") val canBeDeleted: Boolean = true,
    @SerialName("app_name") val appName: String? = null,
    @SerialName("file_name") val fileName: String? = null,
    @SerialName("file_url") val fileUrl: String? = null,
    @SerialName("creator_steam_id") val creatorSteamId: String? = null,
)

@Serializable
data class WorkshopTag(
    @SerialName("tag") val tag: String,
    @SerialName("internal_name") val internalName: String = "",
)

/**
 * Wallpaper type, derived from the workshop item's tags.
 *
 * WE tags (from research): Scene, Video, Web, Application, Image, Playlist.
 * Each maps to a different rendering pipeline on the PC side and a different
 * handling strategy in our pairing proxy.
 */
enum class WallpaperType(val displayName: String, val tagString: String) {
    SCENE("Scene", "Scene"),
    VIDEO("Video", "Video"),
    WEB("Web", "Web"),
    APPLICATION("Application", "Application"),
    IMAGE("Image", "Image"),
    PLAYLIST("Playlist", "Playlist"),
    UNKNOWN("Unknown", "");

    companion object {
        fun fromTags(tags: List<WorkshopTag>): WallpaperType {
            val tagSet = tags.map { it.tag.lowercase() }.toSet()
            return entries.firstOrNull { wt -> tagSet.contains(wt.tagString.lowercase()) } ?: UNKNOWN
        }
    }
}

/**
 * Convenience wrapper that combines the workshop metadata with our locally-derived type.
 */
data class WorkshopItemTyped(
    val item: WorkshopItem,
    val type: WallpaperType,
) {
    val id get() = item.id
    val title get() = item.title
    val previewUrl get() = item.previewUrl
    val creator get() = item.creator
    val subscriptions get() = item.subscriptions
    val tags get() = item.tags
    val fileSizeFormatted: String
        get() {
            val bytes = item.fileSize
            return when {
                bytes < 1024 -> "$bytes B"
                bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
                bytes < 1024 * 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024))
                else -> "%.2f GB".format(bytes / (1024.0 * 1024 * 1024))
            }
        }
}
