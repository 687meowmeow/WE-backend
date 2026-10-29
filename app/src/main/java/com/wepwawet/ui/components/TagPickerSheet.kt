package com.wepwawet.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.wepwawet.data.model.WorkshopTags
import com.wepwawet.log.WepWawetLogger

/**
 * A grouping of related workshop tags for display in the [TagPickerSheet].
 *
 * The sheet renders tags in ordered sections; each section has its own header
 * with a "Turn off all" checkbox that toggles every tag in the section between
 * ON (visible) and OFF (excluded) in one tap.
 */
@Immutable
data class TagSection(val title: String, val tags: List<String>)

/**
 * The canonical list of tag sections shown in the Filter sheet.
 *
 * Order matters — the layout matches WE's screenshot:
 *
 *   1. Mobile Resolutions (Portrait SD/720x1280/1080x1920/1440x2560/2160x3840,
 *      Other resolution, Dynamic resolution) — DEFAULT ON
 *   2. Standard Resolutions
 *   3. Ultrawide Resolutions
 *   4. Dual Resolutions
 *   5. Triple Resolutions
 *   6. Age Rating
 *   7. Genre
 *   8. Miscellaneous
 *   9. Type (Scene, Video — NOT Web or Application)
 *
 * Tag strings are the canonical Steam Workshop strings (with " x " spacing)
 * — see [WorkshopTags.RESOLUTION_TAGS]. Web and Application type tags are
 * deliberately excluded since those wallpaper types are not mobile-compatible.
 */
val TAG_SECTIONS: List<TagSection> = listOf(
    TagSection(
        title = "Mobile Resolutions",
        tags = listOf(
            "Portrait Standard Definition",
            "Portrait 720 x 1280",
            "Portrait 1080 x 1920",
            "Portrait 1440 x 2560",
            "Portrait 2160 x 3840",
            "Other resolution",
            "Dynamic resolution",
        ),
    ),
    TagSection(
        title = "Standard Resolutions",
        tags = listOf(
            "Standard Definition",
            "1280 x 720",
            "1366 x 768",
            "1920 x 1080",
            "2560 x 1440",
            "3840 x 2160",
        ),
    ),
    TagSection(
        title = "Ultrawide Resolutions",
        tags = listOf(
            "Ultrawide Standard Definition",
            "Ultrawide 2560 x 1080",
            "Ultrawide 3440 x 1440",
        ),
    ),
    TagSection(
        title = "Dual Resolutions",
        tags = listOf(
            "Dual Standard Definition",
            "Dual 3840 x 1080",
            "Dual 5120 x 1440",
            "Dual 7680 x 2160",
        ),
    ),
    TagSection(
        title = "Triple Resolutions",
        tags = listOf(
            "Triple Standard Definition",
            "Triple 4096 x 768",
            "Triple 5760 x 1080",
            "Triple 7680 x 1440",
            "Triple 11520 x 2160",
        ),
    ),
    TagSection(
        title = "Age Rating",
        tags = WorkshopTags.AGE_RATING_TAGS,
    ),
    TagSection(
        title = "Genre",
        tags = WorkshopTags.GENRE_TAGS,
    ),
    TagSection(
        title = "Miscellaneous",
        tags = WorkshopTags.MISC_TAGS,
    ),
    TagSection(
        title = "Type",
        // Scene and Video only — Web and Application are not mobile-compatible
        // and are excluded from the picker entirely.
        tags = listOf("Scene", "Video"),
    ),
)

/**
 * Compute the default set of excluded tags: ONLY the non-mobile resolution
 * tags (Standard, Ultrawide, Dual, Triple Resolutions).
 *
 * This is the v0.5.3 default state — every tag is ON (visible) by default
 * EXCEPT the four non-mobile resolution sections. Specifically:
 *
 *   - Mobile Resolutions:  ALL ON
 *   - Standard Resolutions: ALL OFF (excluded)
 *   - Ultrawide Resolutions: ALL OFF (excluded)
 *   - Dual Resolutions:     ALL OFF (excluded)
 *   - Triple Resolutions:  ALL OFF (excluded)
 *   - Age Rating:           ALL ON
 *   - Genre:                ALL ON
 *   - Miscellaneous:        ALL ON
 *   - Type (Scene, Video):  ALL ON
 *
 * Tag strings are the canonical Steam Workshop strings (with " x " spacing).
 * The user can re-enable any section via the Filter sheet's "Turn on all"
 * checkbox.
 */
fun defaultExcludedTags(): List<String> {
    // Only the four non-mobile resolution sections should be OFF (excluded)
    // by default. Every other section (Mobile Resolutions, Age Rating,
    // Genre, Miscellaneous, Type) is ON.
    val excludedSections = setOf(
        "Standard Resolutions",
        "Ultrawide Resolutions",
        "Dual Resolutions",
        "Triple Resolutions",
    )
    return TAG_SECTIONS
        .filter { it.title in excludedSections }
        .flatMap { it.tags }
        .distinct()
}

/**
 * Modal bottom sheet for picking which tags to EXCLUDE from the browse results
 * and toggling the "Mobile Compatible" filter.
 *
 * v0.5.2 OVERHAUL — the sheet was redesigned to match WE's screenshot:
 *
 *   - Title changed to "Filter" (was previously "Filter" too but the
 *     explanatory text below has been updated to describe sectioned toggling).
 *   - "Mobile Compatible" toggle is now hosted at the TOP of this sheet
 *     (moved off the main filter bar). When ON, the Steam query gets
 *     `kvs[app_workshop_eula_version]=3` plus excludes Application/Web tags.
 *   - Tags are grouped into SECTIONS (see [TAG_SECTIONS]) instead of a flat
 *     list. Each section has its own header with a checkbox that toggles
 *     the whole section between ON and OFF in one tap.
 *   - DEFAULT STATE: only the "Mobile Resolutions" section tags are ON.
 *     All other sections are OFF. See [defaultExcludedTags].
 *   - Tags that are ON are NOT in [excludedTags]. Tags that are OFF ARE in
 *     [excludedTags] (the same semantics as v0.5.1 — only the default state
 *     and the sectioned layout changed).
 *
 * The sheet uses a local mutable working copy so the user can toggle tags
 * without committing until "Apply" is tapped. Cancel (back / scrim) discards
 * the changes.
 *
 * @param visible whether the bottom sheet is currently shown
 * @param excludedTags the currently-excluded tags (initial exclusion state).
 *   Tags NOT in this list are shown as ON; tags in this list are shown as OFF.
 * @param mobileCompatible initial state of the Mobile Compatible toggle.
 * @param onApply invoked with the new exclusion list and the new
 *   mobile-compatible flag when the user taps "Apply".
 * @param onDismiss invoked when the sheet is dismissed by swipe or scrim tap
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun TagPickerSheet(
    visible: Boolean,
    excludedTags: List<String>,
    mobileCompatible: Boolean,
    onApply: (excludedTags: List<String>, mobileCompatible: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    // v0.7.4: the sheet CONTENT is now conditionally composed via `if (visible)`
    // instead of an early `if (!visible) return` above the remember calls.
    // With the early-return, the `remember(visible)` slots were never
    // re-executed with a CHANGED key (they only ever ran while visible=true),
    // so the working draft silently persisted across open/close cycles — a
    // user who toggled tags and swiped the sheet away found their discarded
    // draft (not the current filter) pre-selected on the next open. With the
    // wrap, the entire content leaves composition when hidden, so every open
    // starts from the caller's current values.
    if (visible) {
        TagPickerSheetContent(
            excludedTags = excludedTags,
            mobileCompatible = mobileCompatible,
            onApply = onApply,
            onDismiss = onDismiss,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun TagPickerSheetContent(
    excludedTags: List<String>,
    mobileCompatible: Boolean,
    onApply: (excludedTags: List<String>, mobileCompatible: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // Local mutable working copies so the user can toggle without committing
    // until "Apply" is tapped. Cancel (back / scrim) discards the changes.
    //
    // Semantic: a tag in this set is EXCLUDED (toggled OFF, hidden from
    // results). A tag NOT in this set is ON (visible in results).
    // v0.7.4: plain remember (no key) — the wrapping `if (visible)` in
    // TagPickerSheet guarantees a fresh composition (and therefore a fresh
    // draft initialized from the CURRENT parameters) on every open.
    var workingExcluded by remember { mutableStateOf(excludedTags.toSet()) }
    var workingMobileCompat by remember { mutableStateOf(mobileCompatible) }
    var searchQuery by remember { mutableStateOf("") }

    ModalBottomSheet(
        onDismissRequest = {
            WepWawetLogger.workshop("filter_picker dismissed")
            onDismiss()
        },
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            // === Header ===
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = "Filter",
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = "${workingExcluded.size} excluded",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Spacer(Modifier.size(4.dp))
            Text(
                text = "Toggle a section's checkbox to turn all its tags on/off. " +
                    "Tags that are ON are visible in results.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.size(12.dp))

            // === Mobile Compatible toggle (at the top, before the tag sections) ===
            // Wrapped in a properly-sized Surface with explicit width constraint
            // and padding so the inner Row (icon + label column) does not push the
            // Switch off the right edge when the description text wraps.
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = MaterialTheme.shapes.medium,
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    // The label area is weighted so it absorbs all leftover width,
                    // forcing the description Texts to wrap instead of expanding the
                    // Row past the sheet's edge. The Switch stays anchored to the
                    // right with its natural 36dp width.
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(
                            Icons.Filled.PhoneAndroid,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "Mobile Compatible",
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                "Only show wallpapers with the mobile-compatible " +
                                    "author flag (Scene/Video types).",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Switch(
                        checked = workingMobileCompat,
                        onCheckedChange = {
                            WepWawetLogger.workshop(
                                "filter_picker_mobile_compat_toggled",
                                "enabled" to it,
                            )
                            workingMobileCompat = it
                        },
                    )
                }
            }

            Spacer(Modifier.size(12.dp))

            // === Tag search field ===
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text("Search tags…") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(Icons.Filled.Clear, contentDescription = "Clear")
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            )

            Spacer(Modifier.size(8.dp))

            // === Tag sections (filter by search query first) ===
            val q = searchQuery.trim().lowercase()
            val visibleSections: List<TagSection> = remember(q) {
                if (q.isBlank()) {
                    TAG_SECTIONS
                } else {
                    TAG_SECTIONS
                        .map { section ->
                            section.copy(
                                tags = section.tags.filter {
                                    it.lowercase().contains(q)
                                },
                            )
                        }
                        .filter { it.tags.isNotEmpty() }
                }
            }

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 520.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                visibleSections.forEach { section ->
                    item(key = "section_${section.title}") {
                        TagSectionBlock(
                            section = section,
                            workingExcluded = workingExcluded,
                            onToggleTag = { tag ->
                                val isExcluded = tag in workingExcluded
                                workingExcluded = if (isExcluded) {
                                    workingExcluded - tag
                                } else {
                                    workingExcluded + tag
                                }
                                WepWawetLogger.workshop(
                                    "filter_picker_toggle",
                                    "tag" to tag,
                                    "section" to section.title,
                                    "excluded" to !isExcluded,
                                    "total_excluded" to workingExcluded.size,
                                )
                            },
                            onToggleSection = { turnOn ->
                                // turnOn = true  → section tags should be ON
                                //              (remove them from excluded)
                                // turnOn = false → section tags should be OFF
                                //              (add them to excluded)
                                val updated = workingExcluded.toMutableSet()
                                if (turnOn) {
                                    section.tags.forEach { updated.remove(it) }
                                } else {
                                    section.tags.forEach { updated.add(it) }
                                }
                                workingExcluded = updated
                                WepWawetLogger.workshop(
                                    "filter_picker_toggle_section",
                                    "section" to section.title,
                                    "turn_on" to turnOn,
                                    "section_size" to section.tags.size,
                                    "total_excluded" to workingExcluded.size,
                                )
                            },
                        )
                    }
                }
            }

            Spacer(Modifier.size(12.dp))

            // === Footer: Clear exclusions / Apply ===
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = {
                        WepWawetLogger.workshop("filter_picker_clear_exclusions")
                        // Reset to the v0.5.2 default state — only Mobile
                        // Resolutions ON, all others OFF.
                        workingExcluded = defaultExcludedTags().toSet()
                    },
                    modifier = Modifier.weight(1f),
                ) { Text("Reset to default") }
                Button(
                    onClick = {
                        val result = workingExcluded.toList()
                        WepWawetLogger.workshop(
                            "filter_picker_apply",
                            "excluded_count" to result.size,
                            "excluded" to result.joinToString(","),
                            "mobile_compatible" to workingMobileCompat,
                        )
                        onApply(result, workingMobileCompat)
                    },
                    modifier = Modifier.weight(1f),
                ) { Text("Apply") }
            }

            Spacer(Modifier.size(12.dp))
        }
    }
}

/**
 * Renders a single tag section: header (with section-level toggle checkbox)
 * and a [FlowRow] of [FilterChip]s.
 *
 * @param section the [TagSection] to render
 * @param workingExcluded the current working set of excluded tags
 * @param onToggleTag invoked when a single tag chip is tapped
 * @param onToggleSection invoked with `true` to turn the whole section ON,
 *   `false` to turn it OFF
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TagSectionBlock(
    section: TagSection,
    workingExcluded: Set<String>,
    onToggleTag: (String) -> Unit,
    onToggleSection: (Boolean) -> Unit,
) {
    val excludedInSection = section.tags.count { it in workingExcluded }
    val onCount = section.tags.size - excludedInSection
    // The section checkbox is "checked" (= ON) when ALL tags in the section
    // are NOT excluded. If at least one tag is OFF, the checkbox is unchecked.
    // Tapping it flips the section: if all ON → all OFF; otherwise → all ON.
    val allOn = onCount == section.tags.size
    val someOn = onCount > 0 && !allOn

    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Section header — title + count + "Turn off all" checkbox
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = section.title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = if (allOn) {
                            "All ${section.tags.size} on"
                        } else if (someOn) {
                            "$onCount / ${section.tags.size} on"
                        } else {
                            "All off"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = if (allOn) "Turn off all" else "Turn on all",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Checkbox(
                        checked = allOn,
                        onCheckedChange = { checked ->
                            // Checkbox checked = all ON. If user taps and the
                            // checkbox is now checked (checked=true), we want
                            // to turn all ON. If unchecked, turn all OFF.
                            // Note: onCheckedChange gives the NEW state.
                            onToggleSection(checked)
                        },
                    )
                }
            }

            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
            )

            // Tag chips — selected when NOT excluded (i.e. ON = visible).
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                section.tags.forEach { tag ->
                    val isExcluded = tag in workingExcluded
                    FilterChip(
                        // Inverted semantic: chip is SELECTED when the
                        // tag is NOT excluded (i.e. it's visible in
                        // results). Selected = ON = shown.
                        selected = !isExcluded,
                        onClick = { onToggleTag(tag) },
                        label = {
                            Text(
                                text = tag,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                    )
                }
            }
        }
    }
}
