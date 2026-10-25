package com.wepwawet.data.model

/**
 * Complete Wallpaper Engine Steam Workshop tag taxonomy.
 *
 * Extracted from Steam's /workshop/browse?appid=431960 `declaredTags` JSON.
 * These are the valid values for `requiredtags[]` and `excludedtags[]` URL params.
 *
 * NOTE: "Mobile" is NOT a Steam Workshop tag — mobile compatibility is determined
 * client-side based on wallpaper type (Scene/Video are mobile-compatible; Web/App
 * are NOT). Use [isMobileCompatible] to check.
 */
object WorkshopTags {

    // ==================== TYPE (single-select) ====================
    val TYPE_TAGS = listOf("Scene", "Video", "Application", "Web")

    // ==================== CATEGORY (single-select) ====================
    val CATEGORY_TAGS = listOf("Wallpaper", "Preset", "Asset")

    // ==================== AGE RATING (single-select, "maturity") ====================
    val AGE_RATING_TAGS = listOf("Everyone", "Questionable", "Mature")

    // ==================== GENRE (multi-select) ====================
    val GENRE_TAGS = listOf(
        "Abstract", "Animal", "Anime", "Cartoon", "CGI", "Cyberpunk",
        "Fantasy", "Game", "Girls", "Guys", "Landscape", "Medieval",
        "Memes", "MMD", "Music", "Nature", "Pixel art", "Relaxing",
        "Retro", "Sci-Fi", "Sports", "Technology", "Television",
        "Vehicle", "Unspecified",
    )

    // ==================== RESOLUTION (single-select) ====================
    val RESOLUTION_TAGS = listOf(
        "Standard Definition",
        "1280 x 720",
        "1366 x 768",
        "1920 x 1080",
        "2560 x 1440",
        "3840 x 2160",
        "Ultrawide Standard Definition",
        "Ultrawide 2560 x 1080",
        "Ultrawide 3440 x 1440",
        "Dual Standard Definition",
        "Dual 3840 x 1080",
        "Dual 5120 x 1440",
        "Dual 7680 x 2160",
        "Triple Standard Definition",
        "Triple 4096 x 768",
        "Triple 5760 x 1080",
        "Triple 7680 x 1440",
        "Triple 11520 x 2160",
        "Portrait Standard Definition",
        "Portrait 720 x 1280",
        "Portrait 1080 x 1920",
        "Portrait 1440 x 2560",
        "Portrait 2160 x 3840",
        "Other resolution",
        "Dynamic resolution",
    )

    /** Portrait resolutions (mobile-friendly). */
    val PORTRAIT_RESOLUTIONS = listOf(
        "Portrait Standard Definition",
        "Portrait 720 x 1280",
        "Portrait 1080 x 1920",
        "Portrait 1440 x 2560",
        "Portrait 2160 x 3840",
    )

    /**
     * Mobile-friendly resolutions (portrait + Other + Dynamic).
     * These are put at the TOP of the filter list since they're most relevant
     * for phone users.
     */
    val MOBILE_RESOLUTIONS = listOf(
        "Portrait Standard Definition",
        "Portrait 720 x 1280",
        "Portrait 1080 x 1920",
        "Portrait 1440 x 2560",
        "Portrait 2160 x 3840",
        "Other resolution",
        "Dynamic resolution",
    )

    /**
     * Tags that should NOT appear in the filter picker (because they're for
     * non-mobile-compatible wallpaper types). The user shouldn't be able to
     * select Web or Application type tags since those wallpapers are filtered out.
     */
    val EXCLUDED_TYPE_TAGS = listOf("Web", "Application")

    // ==================== MISCELLANEOUS (multi-select) ====================
    val MISC_TAGS = listOf(
        "Approved", "Audio responsive", "3D", "Customizable", "Puppet Warp",
        "HDR", "Media Integration", "User Shortcut", "Video Texture", "Asset Pack",
    )

    // ==================== ASSET TYPE (single-select) ====================
    val ASSET_TYPE_TAGS = listOf(
        "Particle", "Image", "Sound", "Model", "Text", "Sprite",
        "Fullscreen", "Composite", "Script", "Effect", "Scripted Layer",
    )

    // ==================== ASSET GENRE (single-select) ====================
    val ASSET_GENRE_TAGS = listOf(
        "Audio Visualizer", "Background", "Character", "Clock", "Fire",
        "Interactive", "Magic", "Post Processing", "Smoke", "Space",
    )

    // ==================== SCRIPT TYPE (single-select) ====================
    val SCRIPT_TYPE_TAGS = listOf(
        "Boolean", "Number", "Vec2", "Vec3", "Vec4", "String",
        "No Animation", "Oversized",
    )

    // ==================== HIDDEN/ADMIN (multi-select) ====================
    val HIDDEN_TAGS = listOf(
        "Official Workshop Tutorial", "Verified", "Multi-monitor optimized",
    )

    /**
     * All tags that should appear in the filter picker, ordered with mobile
     * resolutions at the top.
     *
     * NOTE: "Web" and "Application" type tags are excluded since those wallpaper
     * types are not mobile-compatible.
     *
     * IMPORTANT: This declaration must come AFTER [MISC_TAGS] and [HIDDEN_TAGS]
     * because Kotlin object property initializers run in declaration order —
     * forward references to other `val`s in the same object are forbidden at
     * compile time.
     */
    val FILTER_TAGS: List<String> = buildList {
        // Mobile resolutions first
        addAll(MOBILE_RESOLUTIONS)
        // Other resolutions
        addAll(RESOLUTION_TAGS.filter { it !in MOBILE_RESOLUTIONS })
        // Age Rating (maturity)
        addAll(AGE_RATING_TAGS)
        // Type (excluding Web and Application)
        addAll(TYPE_TAGS.filter { it !in EXCLUDED_TYPE_TAGS })
        // Genre
        addAll(GENRE_TAGS)
        // Miscellaneous
        addAll(MISC_TAGS)
        // Category
        addAll(CATEGORY_TAGS)
        // Hidden/admin
        addAll(HIDDEN_TAGS)
    }.distinct()

    /** All tags flattened (for search/autocomplete). */
    val ALL_TAGS: List<String> = (
        TYPE_TAGS + CATEGORY_TAGS + AGE_RATING_TAGS + GENRE_TAGS +
        RESOLUTION_TAGS + MISC_TAGS + ASSET_TYPE_TAGS + ASSET_GENRE_TAGS +
        SCRIPT_TYPE_TAGS + HIDDEN_TAGS
    ).distinct()

    /**
     * Determine if a workshop item is compatible with WE Android.
     *
     * Per WE PC's canUploadWallpaperToMobileDevice JS logic (from wallpaperui.exe's
     * embedded scripts.js):
     *   "Scene" === e.type || "Video" === e.type
     *
     * Only Scene and Video types are mobile-compatible. Image, Web, Application,
     * Playlist, and Unknown types are NOT compatible.
     *
     * (Web wallpapers use HTML/CSS/JS which WE Android doesn't render;
     *  Application wallpapers are interactive EXE-based; Image type is not
     *  in the WE PC mobile upload whitelist.)
     */
    fun isMobileCompatible(type: WallpaperType, tags: List<String>): Boolean {
        // Only Scene and Video types are mobile-compatible per WE PC's logic
        return type == WallpaperType.SCENE || type == WallpaperType.VIDEO
    }

    /** Extract the age rating (maturity) from tags. */
    fun getMaturity(tags: List<String>): Maturity {
        return when {
            tags.any { it.equals("Mature", ignoreCase = true) } -> Maturity.MATURE
            tags.any { it.equals("Questionable", ignoreCase = true) } -> Maturity.QUESTIONABLE
            tags.any { it.equals("Everyone", ignoreCase = true) } -> Maturity.EVERYONE
            else -> Maturity.UNKNOWN
        }
    }

    /** Extract the resolution tag from tags. */
    fun getResolution(tags: List<String>): String? {
        return tags.firstOrNull { tag ->
            RESOLUTION_TAGS.any { it.equals(tag, ignoreCase = true) }
        }
    }

    /** Check if the resolution is portrait (mobile-friendly). */
    fun isPortraitResolution(tags: List<String>): Boolean {
        val res = getResolution(tags) ?: return false
        return PORTRAIT_RESOLUTIONS.any { it.equals(res, ignoreCase = true) }
    }

    /** Maturity rating enum. */
    enum class Maturity(val displayName: String, val tag: String) {
        EVERYONE("Everyone", "Everyone"),
        QUESTIONABLE("Questionable", "Questionable"),
        MATURE("Mature", "Mature"),
        UNKNOWN("Unrated", ""),
        ;
    }
}
