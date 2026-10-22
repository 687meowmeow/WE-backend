package com.wepwawet.data.model

import com.google.gson.annotations.SerializedName

/**
 * Steam Workshop item — the subset of fields we care about, parsed from the
 * ISteamRemoteStorage/GetPublishedFileDetails/v1/ API response with Gson.
 *
 * Steam API raw response (anonymous access confirmed):
 *   {response:{result:1,publishedfiledetails:[{publishedfileid,title,description,
 *     file_size,preview_url,subscriptions,favorited,views,
 *     tags:[{tag:"Video"},{tag:"Scene"}],
 *     time_created,time_updated,...}]}}
 *
 * Note: the API returns `file_url` as empty for paid apps (including Wallpaper Engine),
 * so we cannot download the actual wallpaper content via this anonymous endpoint.
 * That's why we run the pairing proxy + the user's official WE Android app for the
 * actual content transfer — we only use this for browse/search/discovery.
 *
 * For browsing/searching in the UI, this is sufficient.
 *
 * Gson needs the no-arg constructor that Kotlin data classes provide. The ProGuard
 * rules in app/proguard-rules.pro keep `com.wepwawet.data.model.**` so R8 won't
 * strip or rename these fields.
 *
 * Tags: the Steam API returns `tags` as an array of `{"tag":"…"}` objects. To make
 * the data class ergonomic, we flatten to `List<String>` via a custom Gson
 * deserializer registered on [com.wepwawet.data.SteamApiService].
 */
data class WorkshopItem(
    @SerializedName("publishedfileid") val publishedfileid: String = "",
    @SerializedName("title") val title: String = "",
    @SerializedName("description") val description: String = "",
    @SerializedName("file_size") val fileSize: Long = 0L,
    @SerializedName("preview_url") val previewUrl: String? = null,
    @SerializedName("subscriptions") val subscriptions: Long = 0L,
    @SerializedName("favorited") val favorited: Long = 0L,
    @SerializedName("views") val views: Long = 0L,
    @SerializedName("lifetime_subscriptions") val lifetimeSubscriptions: Long = 0L,
    @SerializedName("lifetime_favorited") val lifetimeFavorited: Long = 0L,
    @SerializedName("tags") val tags: List<String> = emptyList(),
    @SerializedName("time_created") val timeCreated: Long = 0L,
    @SerializedName("time_updated") val timeUpdated: Long = 0L,
    @SerializedName("creator") val creator: String = "",
    @SerializedName("creator_app_id") val creatorAppId: Long = 0L,
    @SerializedName("creator_steam_id") val creatorSteamId: String? = null,
    @SerializedName("visibility") val visibility: Int = 0,
    @SerializedName("banned") val banned: Boolean = false,
    @SerializedName("ban_reason") val banReason: String? = null,
    @SerializedName("banner") val banner: String? = null,
    @SerializedName("app_name") val appName: String? = null,
    @SerializedName("file_name") val fileName: String? = null,
    @SerializedName("file_url") val fileUrl: String? = null,
    /**
     * Wallpaper type, derived locally from [tags] — never sent by Steam.
     * Marked `@Transient` so Gson ignores it on (de)serialization; we compute
     * it on [WorkshopItemTyped] construction.
     */
    @Transient val type: WallpaperType = WallpaperType.UNKNOWN,
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
        /**
         * Derive the wallpaper type from the workshop tag list. Steam returns
         * tags as e.g. `["Video","1920x1080","Anime"]` — we look for the first
         * tag that exactly matches a known WE type. Falls back to [UNKNOWN].
         */
        fun fromTags(tags: List<String>): WallpaperType {
            val tagSet = tags.map { it.lowercase() }.toSet()
            return entries.firstOrNull { wt ->
                wt != UNKNOWN && tagSet.contains(wt.tagString.lowercase())
            } ?: UNKNOWN
        }
    }
}

/**
 * Convenience wrapper that combines the workshop metadata with our locally-derived type.
 * Created by [com.wepwawet.data.SteamApiService] after Gson deserialization.
 */
data class WorkshopItemTyped(
    val item: WorkshopItem,
    val type: WallpaperType,
) {
    val id: String get() = item.publishedfileid
    val title: String get() = item.title
    val previewUrl: String? get() = item.previewUrl
    val creator: String get() = item.creator
    val subscriptions: Long get() = item.subscriptions
    val favorited: Long get() = item.favorited
    val views: Long get() = item.views
    val tags: List<String> get() = item.tags
    val timeCreated: Long get() = item.timeCreated
    val timeUpdated: Long get() = item.timeUpdated
    val description: String get() = item.description

    /**
     * Human-readable file size string (B / KB / MB / GB).
     * Mirrors the format used by the WE workshop browser.
     */
    val fileSizeFormatted: String
        get() {
            val bytes = item.fileSize
            return when {
                bytes <= 0 -> "—"
                bytes < 1024 -> "$bytes B"
                bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
                bytes < 1024 * 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024))
                else -> "%.2f GB".format(bytes / (1024.0 * 1024 * 1024))
            }
        }

    /**
     * Formatted subscription count (e.g. "1.2M", "53K", "982").
     */
    val subscriptionsFormatted: String
        get() {
            val n = item.subscriptions
            return when {
                n < 1_000 -> n.toString()
                n < 1_000_000 -> "%.1fK".format(n / 1_000.0)
                n < 1_000_000_000 -> "%.1fM".format(n / 1_000_000.0)
                else -> "%.2fB".format(n / 1_000_000_000.0)
            }
        }

    /**
     * Formatted creation date (e.g. "Oct 22, 2026"). Returns "—" if unknown.
     */
    val createdDateFormatted: String
        get() = if (item.timeCreated > 0) {
            val fmt = java.text.SimpleDateFormat("MMM d, yyyy", java.util.Locale.US)
            fmt.format(java.util.Date(item.timeCreated * 1000L))
        } else "—"
}
