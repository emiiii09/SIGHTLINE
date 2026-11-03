// SPDX-License-Identifier: GPL-3.0-or-later

package com.sightline.app

/** Recent audit scores. Pure Kotlin, unit-tested; the activity owns storage I/O. */
object History {
    const val MAX = 20

    data class Entry(val time: Long, val source: String, val score: Int, val count: Int)

    /** Parses lines of `time|source|score|count`; skips malformed ones. */
    fun parse(raw: String?): List<Entry> {
        if (raw.isNullOrBlank()) return emptyList()
        return raw.lines().mapNotNull { line ->
            val parts = line.split("|")
            if (parts.size != 4) return@mapNotNull null
            val time = parts[0].toLongOrNull() ?: return@mapNotNull null
            val score = parts[2].toIntOrNull() ?: return@mapNotNull null
            val count = parts[3].toIntOrNull() ?: return@mapNotNull null
            Entry(time, parts[1], score, count)
        }
    }

    fun format(entries: List<Entry>): String =
        entries.joinToString("\n") { "${it.time}|${it.source}|${it.score}|${it.count}" }

    /** Newest first, capped; sources never carry the separator. */
    fun add(entries: List<Entry>, entry: Entry): List<Entry> =
        (listOf(entry.copy(source = entry.source.replace("|", "/"))) + entries).take(MAX)
}
