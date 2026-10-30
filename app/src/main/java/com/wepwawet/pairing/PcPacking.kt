package com.wepwawet.pairing

/**
 * PC-exact packing rules — the container-level behaviors extracted from
 * wallpaperui.exe's convertProjectToMobile (0x14003d1e0) and verified
 * byte-for-byte against a real PC-produced .mpkg.
 */
object PcPacking {

    /**
     * The PC's entry ordering: the transcoder packs the transcoded temp
     * directory with a depth-first recursive enumeration, which on NTFS
     * yields entries in CASE-INSENSITIVE alphabetical order. That is why
     * `materials/masks/…` comes BEFORE `materials/MSK12003…` in every real
     * PC .mpkg (M-a < M-s under the NTFS upcase table) — a case-sensitive
     * sort would put MSK12003 first.
     *
     * Verified: the ground-truth .mpkg's 52-entry order equals
     * `sorted(entries, key = path.lowercase())` exactly.
     */
    val PC_ENTRY_ORDER: Comparator<String> =
        compareBy({ it.lowercase() }, { it })

    /**
     * Drop rule for sound files — wallpaperui.exe 0x14003ffdd: when a file's
     * path contains a "sounds" segment, `std::filesystem::remove` deletes it
     * before packing (mobile packages carry no sound assets).
     */
    fun isSoundsPath(path: String): Boolean =
        path.split('/', '\\').any { it.equals("sounds", ignoreCase = true) }

    /**
     * Append `"workshopurl"` to project.json exactly the way the PC does
     * (wallpaperui.exe 0x1400416b8 — the field is written by WE's own JSON
     * serializer, so it lands as the LAST key with tab indent, `" : "`
     * spacing, and the file's existing CRLF line endings).
     *
     * Because the .pkg's project.json was written by that same serializer,
     * a textual insertion reproduces the PC output byte-for-byte:
     *
     *      "\t\"version\" : 0\r\n}"              (original tail)
     *   →  "\t\"version\" : 0,\r\n\t\"workshopurl\" : \"<url>\"\r\n}"
     *
     * Returns the input unchanged when a workshopurl is already present or
     * the JSON has no root object to append into.
     */
    fun addWorkshopUrl(projectJsonText: String, url: String): String {
        if (projectJsonText.contains("\"workshopurl\"")) return projectJsonText
        val lastBrace = projectJsonText.lastIndexOf('}')
        if (lastBrace <= 0) return projectJsonText
        val head = projectJsonText.substring(0, lastBrace).trimEnd()
        val tail = projectJsonText.substring(lastBrace)
        val nl = if (projectJsonText.contains("\r\n")) "\r\n" else "\n"
        return head + ",$nl\t\"workshopurl\" : \"$url\"$nl" + tail
    }
}
