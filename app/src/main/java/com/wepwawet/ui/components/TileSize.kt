package com.wepwawet.ui.components

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Tile size for the workshop browser grid.
 *
 * Controls both the height of each wallpaper card and the number of columns
 * shown in the [androidx.compose.foundation.lazy.grid.LazyVerticalGrid].
 *
 * This mirrors Steam Workshop's own browser, which lets users pick between
 * compact / medium / large thumbnails.
 *
 * - [COMPACT] : small tiles, more per row. Two columns on phone, three on tablet.
 * - [MEDIUM]  : default, ~Steam's default tile. One column on phone, two on tablet.
 * - [LARGE]   : big tiles for preview-heavy browsing. One column on phone, one on tablet.
 *
 * Selected via the "Tile: ▼" dropdown in [com.wepwawet.ui.screens.WorkshopBrowserScreen].
 */
enum class TileSize(val displayName: String) {
    COMPACT("Compact"),
    MEDIUM("Medium"),
    LARGE("Large"),
    ;

    /** Height of each wallpaper card in dp. */
    val cardHeight: Dp
        get() = when (this) {
            COMPACT -> 120.dp
            MEDIUM -> 180.dp
            LARGE -> 240.dp
        }

    /**
     * Number of grid columns for a given screen width bucket.
     *
     * @param isCompact `true` if the screen is phone-width (window size class Compact),
     *   `false` if it's tablet/desktop-width (Medium/Expanded).
     */
    fun columnsFor(isCompact: Boolean): Int = when (this) {
        COMPACT -> if (isCompact) 3 else 5
        MEDIUM -> if (isCompact) 2 else 3
        LARGE -> if (isCompact) 1 else 2
    }
}
