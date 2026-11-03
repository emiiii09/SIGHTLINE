// SPDX-License-Identifier: GPL-3.0-or-later

package com.sightline.app

/** Shareable plain-text audit report. Pure Kotlin, unit-tested. */
object Report {
    fun build(lang: String, source: String, score: Int, issues: List<Issue>): String {
        val sb = StringBuilder()
        sb.appendLine(String.format(Strings.get(lang, "reportHead"), source, score))
        if (issues.isEmpty()) {
            sb.appendLine(Strings.get(lang, "noIssues"))
        } else {
            for (issue in issues) {
                sb.appendLine("- ${sevName(lang, issue)} · ${issue.type}: ${issue.description}")
            }
        }
        return sb.toString().trim()
    }

    /**
     * Full fix plan: verdict plus every finding with its baseline fix and,
     * when already requested on its card, the cached AI fix. Never triggers
     * new AI calls — unrequested fixes point back to their card.
     */
    fun buildPlan(
        lang: String,
        source: String,
        score: Int,
        issues: List<Issue>,
        verdict: String,
        aiFixes: Map<Int, String>,
    ): String {
        val sb = StringBuilder()
        sb.appendLine(String.format(Strings.get(lang, "planHead"), source, score))
        sb.appendLine(
            String.format(
                Strings.get(lang, "planVerdict"),
                verdict.ifBlank { Strings.get(lang, "planAskAi") },
            ),
        )
        if (issues.isEmpty()) {
            sb.appendLine(Strings.get(lang, "noIssues"))
            return sb.toString().trim()
        }
        for (issue in issues) {
            sb.appendLine()
            sb.appendLine("#${issue.marker} · ${sevName(lang, issue)} · ${issue.type}")
            sb.appendLine(issue.description)
            sb.appendLine(String.format(Strings.get(lang, "baseline"), issue.suggestion))
            val ai = aiFixes[issue.marker]
            sb.appendLine(
                if (ai.isNullOrBlank()) {
                    String.format(Strings.get(lang, "planAiMissing"), issue.marker)
                } else {
                    ai.trim()
                },
            )
        }
        return sb.toString().trim()
    }

    /** Signed delta for the before/after line: +12, -3, 0. */
    fun signed(delta: Int): String = if (delta > 0) "+$delta" else delta.toString()

    /**
     * Before/after line rendered in the caller's language. Numbers in, text
     * out: callers keep the numbers so a language change re-renders instead
     * of showing the old language.
     */
    fun deltaLine(lang: String, prev: Int, score: Int): String =
        String.format(Strings.get(lang, "beforeAfter"), prev, score, signed(score - prev))

    private fun sevName(lang: String, issue: Issue): String =
        Strings.get(lang, "sev_" + issue.severity.name.lowercase())
}
