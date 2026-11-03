// SPDX-License-Identifier: GPL-3.0-or-later

package com.sightline.app

/** Severity of an accessibility finding, ordered by demo priority. */
enum class Severity { ERROR, WARNING, INFO }

/**
 * One accessibility finding. Pure data — no Android dependencies.
 * Serializable so rotation keeps the results (bundle only, never storage).
 */
data class Issue(
    val type: String,
    val severity: Severity,
    val element: String,
    val description: String,
    val suggestion: String,
    /** 1-based number matching the pink badge in the visual preview. */
    var marker: Int = 0,
) : java.io.Serializable
