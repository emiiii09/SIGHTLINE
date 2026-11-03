// SPDX-License-Identifier: GPL-3.0-or-later

package com.sightline.app

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import kotlin.math.pow

/**
 * Deterministic accessibility audit of a raw HTML document.
 *
 * Pure Kotlin + Jsoup: no Android classes, so it runs in local unit tests.
 * Findings come out in English or Spanish ([lang]); Spanish uses voseo,
 * like the rest of the app. The AI layer ([MetaAiClient]) builds on top.
 */
object AccessibilityAnalyzer {

    /** WCAG AA threshold for normal text. */
    const val CONTRAST_THRESHOLD = 4.5

    // Unstick preview chrome first: fixed/sticky banners would float detached
    // from their marks (or follow the scroll), so the marked copy scrolls as
    // one static piece. It goes first so the badge rules below win every tie.
    private const val MARK_CSS =
        "body :not(.slmark){position:static !important;}" +
            ".slmark{outline:3px solid #F4A7C4 !important;outline-offset:2px;position:relative !important;}" +
            ".slwrap{display:inline-block;}" +
            ".slmark::after{content:attr(data-sl);position:absolute;top:2px;left:2px;" +
            "background:#F4A7C4;color:#000;font:bold 12px/1.4 sans-serif;padding:1px 8px;" +
            "border-radius:999px;z-index:999999;white-space:nowrap;}"

    fun analyze(html: String, lang: String = "en"): List<Issue> {
        val doc = Jsoup.parse(html)
        val issues = mutableListOf<Issue>()
        issues += checkLang(doc, lang)
        issues += checkTitle(doc, lang)
        issues += checkViewport(doc, lang)
        issues += checkImages(doc, lang)
        issues += checkInputs(doc, lang)
        issues += checkButtons(doc, lang)
        issues += checkLinks(doc, lang)
        issues += checkHeadings(doc, lang)
        issues += checkContrast(doc, lang)
        issues.forEachIndexed { index, issue -> issue.marker = index + 1 }
        return issues
    }

    /**
     * Returns a display copy of the page with every element-backed finding
     * tagged and an injected stylesheet drawing pink numbered badges.
     * Badge numbers match [Issue.marker], so cards and preview line up.
     * Replaced elements (img, input) are wrapped — their badges would not
     * render otherwise. Head-level findings (lang, title, viewport) have no
     * visible element and are previewed by their cards only.
     */
    fun highlight(html: String, lang: String = "en"): String {
        val doc = Jsoup.parse(html)
        var n = 0
        val tag: (Element) -> Unit = { el ->
            n++
            val target = if (el.tagName() == "img" || el.tagName() == "input") {
                val wrap = doc.createElement("span")
                wrap.attr("class", "slmark slwrap")
                el.replaceWith(wrap)
                wrap.appendChild(el)
                wrap
            } else {
                el.addClass("slmark")
                el
            }
            val prevMarks = target.attr("data-sl")
            target.attr("data-sl", if (prevMarks.isBlank()) "$n" else "$prevMarks,$n")
        }
        // Same traversal order as analyze(): numbering stays identical.
        checkImages(doc, lang, tag)
        checkInputs(doc, lang, tag)
        checkButtons(doc, lang, tag)
        checkLinks(doc, lang, tag)
        checkHeadings(doc, lang, tag)
        checkContrast(doc, lang, tag)
        val style = doc.createElement("style")
        style.text(MARK_CSS)
        doc.head().appendChild(style)
        return doc.html()
    }

    /** Picks the English or Spanish wording. */
    private fun t(lang: String, en: String, es: String): String =
        if (lang == "es") es else en

    /** 0-100 score penalising errors most. Shown in the demo header. */
    fun score(issues: List<Issue>): Int {
        var s = 100
        for (i in issues) {
            s -= when (i.severity) {
                Severity.ERROR -> 12
                Severity.WARNING -> 5
                Severity.INFO -> 1
            }
        }
        return s.coerceAtLeast(0)
    }

    // -- checks -------------------------------------------------------------

    private fun checkLang(doc: Document, lang: String): List<Issue> {
        val code = doc.selectFirst("html")?.attr("lang")?.trim().orEmpty()
        if (code.isNotEmpty()) return emptyList()
        return listOf(
            Issue(
                type = "MISSING_LANG",
                severity = Severity.WARNING,
                element = "<html>",
                description = t(
                    lang,
                    "The page declares no lang attribute, so screen readers " +
                        "cannot pick the right pronunciation.",
                    "La página no declara idioma, así los lectores de pantalla " +
                        "no saben cómo pronunciarla.",
                ),
                suggestion = t(
                    lang,
                    "Add lang to the html tag, e.g. <html lang=\"en\">.",
                    "Agregá lang a la etiqueta html, ej. <html lang=\"es\">.",
                ),
            ),
        )
    }

    private fun checkTitle(doc: Document, lang: String): List<Issue> {
        val title = doc.selectFirst("title")?.text()?.trim().orEmpty()
        if (title.isNotEmpty()) return emptyList()
        return listOf(
            Issue(
                type = "MISSING_TITLE",
                severity = Severity.WARNING,
                element = "<head>",
                description = t(
                    lang,
                    "The page has no usable <title>, so screen-reader and tab " +
                        "users get no page name.",
                    "La página no tiene un <title> útil, así no se sabe su nombre.",
                ),
                suggestion = t(
                    lang,
                    "Add a concise <title> describing the page.",
                    "Agregá un <title> corto que describa la página.",
                ),
            ),
        )
    }

    private fun checkViewport(doc: Document, lang: String): List<Issue> {
        val hasViewport = doc.select("meta").any { meta ->
            meta.attr("name").equals("viewport", ignoreCase = true)
        }
        if (hasViewport) return emptyList()
        return listOf(
            Issue(
                type = "MISSING_VIEWPORT",
                severity = Severity.WARNING,
                element = "<head>",
                description = t(
                    lang,
                    "No viewport meta tag, so the page may not scale " +
                        "properly on phones and zoom may break.",
                    "Sin etiqueta viewport, la página puede verse mal en celulares.",
                ),
                suggestion = t(
                    lang,
                    "Add <meta name=\"viewport\" " +
                        "content=\"width=device-width, initial-scale=1\">.",
                    "Agregá <meta name=\"viewport\" " +
                        "content=\"width=device-width, initial-scale=1\">.",
                ),
            ),
        )
    }

    private fun checkImages(
        doc: Document,
        lang: String,
        tag: (Element) -> Unit = {},
    ): List<Issue> {
        // toList(): Elements declares its own filter(), so force Kotlin stdlib ops.
        return doc.select("img").toList().filter { !it.hasAttr("alt") }.map { img ->
            tag(img)
            Issue(
                type = "IMG_MISSING_ALT",
                severity = Severity.ERROR,
                element = snippet(img),
                description = t(
                    lang,
                    "Image has no alt attribute, so screen-reader users " +
                        "miss its content entirely.",
                    "La imagen no tiene atributo alt, así quienes usan lector " +
                        "de pantalla pierden su contenido.",
                ),
                suggestion = t(
                    lang,
                    "Add a contextual alt. Decorative only? Use alt=\"\".",
                    "Agregá un alt acorde al contexto. ¿Es decorativa? Usá alt=\"\".",
                ),
            )
        }
    }

    private fun checkInputs(
        doc: Document,
        lang: String,
        tag: (Element) -> Unit = {},
    ): List<Issue> {
        val labelForIds = doc.select("label[for]").eachAttr("for").toSet()
        return doc.select("input").toList().filter { input ->
            val type = input.attr("type").lowercase()
            if (type in setOf("hidden", "submit", "button", "image")) return@filter false
            if (input.hasAttr("aria-label") && input.attr("aria-label").isNotBlank()) return@filter false
            if (input.hasAttr("aria-labelledby") || input.hasAttr("title")) return@filter false
            val id = input.attr("id")
            if (id.isNotBlank() && labelForIds.contains(id)) return@filter false
            input.parents().toList().none { it.tagName() == "label" }
        }.map { input ->
            tag(input)
            Issue(
                type = "INPUT_MISSING_LABEL",
                severity = Severity.ERROR,
                element = snippet(input),
                description = t(
                    lang,
                    "Form field has no accessible label, so its purpose is " +
                        "unclear to assistive technology.",
                    "El campo no tiene etiqueta accesible, así no queda claro " +
                        "para qué sirve.",
                ),
                suggestion = t(
                    lang,
                    "Link a <label for=\"...\">, or add aria-label.",
                    "Vinculá un <label for=\"...\"> o agregá un aria-label.",
                ),
            )
        }
    }

    private fun checkButtons(
        doc: Document,
        lang: String,
        tag: (Element) -> Unit = {},
    ): List<Issue> {
        return doc.select("button").toList().filter { btn ->
            btn.text().isBlank() &&
                !btn.hasAttr("aria-label") &&
                !btn.hasAttr("aria-labelledby") &&
                btn.select("img[alt]").isEmpty()
        }.map { btn ->
            tag(btn)
            Issue(
                type = "BUTTON_NO_NAME",
                severity = Severity.ERROR,
                element = snippet(btn),
                description = t(
                    lang,
                    "Button has no accessible name, so screen-reader users " +
                        "cannot tell what it does.",
                    "El botón no tiene nombre accesible, así no se sabe qué hace.",
                ),
                suggestion = t(
                    lang,
                    "Add visible text or an aria-label, e.g. aria-label=\"Close menu\".",
                    "Agregá texto visible o un aria-label, ej. aria-label=\"Cerrar menú\".",
                ),
            )
        }
    }

    private fun checkLinks(
        doc: Document,
        lang: String,
        tag: (Element) -> Unit = {},
    ): List<Issue> {
        return doc.select("a[href]").toList().filter { a ->
            a.text().isBlank() && !a.hasAttr("aria-label") && !a.hasAttr("aria-labelledby")
        }.map { a ->
            tag(a)
            Issue(
                type = "LINK_NO_NAME",
                severity = Severity.WARNING,
                element = snippet(a),
                description = t(
                    lang,
                    "Link has no text, so its destination is announced as " +
                        "meaningless (e.g. just the URL).",
                    "El enlace no tiene texto, así su destino se anuncia sin sentido.",
                ),
                suggestion = t(
                    lang,
                    "Add descriptive link text or an aria-label.",
                    "Agregá un texto descriptivo o un aria-label.",
                ),
            )
        }
    }

    private fun checkHeadings(
        doc: Document,
        lang: String,
        tag: (Element) -> Unit = {},
    ): List<Issue> {
        val issues = mutableListOf<Issue>()
        var prev = 0
        for (h in doc.select("h1, h2, h3, h4, h5, h6")) {
            val level = h.tagName().substring(1).toInt()
            if (h.text().isBlank()) {
                tag(h)
                issues += Issue(
                    type = "EMPTY_HEADING",
                    severity = Severity.WARNING,
                    element = snippet(h),
                    description = t(
                        lang,
                        "Empty h$level heading adds noise to the outline " +
                            "screen readers use to navigate.",
                        "El título h$level vacío agrega ruido al esquema que usan " +
                            "los lectores para navegar.",
                    ),
                    suggestion = t(
                        lang,
                        "Remove the empty heading or add a short title.",
                        "Quitá el título vacío o agregale un texto corto.",
                    ),
                )
            }
            if (prev != 0 && level > prev + 1) {
                tag(h)
                issues += Issue(
                    type = "HEADING_ORDER",
                    severity = Severity.WARNING,
                    element = snippet(h),
                    description = t(
                        lang,
                        "Heading level jumps from h$prev to h$level, which " +
                            "breaks the document outline used for navigation.",
                        "El nivel salta de h$prev a h$level y rompe el esquema " +
                            "del documento.",
                    ),
                    suggestion = t(
                        lang,
                        "Keep heading levels sequential; do not skip.",
                        "Mantené los niveles en orden, sin saltos.",
                    ),
                )
            }
            prev = level
        }
        return issues
    }

    private fun checkContrast(
        doc: Document,
        lang: String,
        tag: (Element) -> Unit = {},
    ): List<Issue> {
        val issues = mutableListOf<Issue>()
        val bodyBg = doc.selectFirst("body")?.let { bgOf(it) } ?: WHITE
        for (el in doc.select("[style]")) {
            val style = el.attr("style").lowercase()
            val fg = styleColor(style, "color") ?: continue
            val bg = styleColor(style, "background-color") ?: bgOf(el) ?: bodyBg
            val ratio = contrastRatio(fg, bg)
            if (el.ownText().isBlank()) continue
            if (ratio < CONTRAST_THRESHOLD) {
                tag(el)
                issues += Issue(
                    type = "LOW_CONTRAST",
                    severity = Severity.WARNING,
                    element = snippet(el),
                    description = String.format(
                        java.util.Locale.US,
                        t(
                            lang,
                            "Text contrast ratio is %.2f:1, below the WCAG AA " +
                                "minimum of 4.5:1 for normal text.",
                            "El contraste del texto es %.2f:1, menor al mínimo WCAG AA " +
                                "de 4.5:1.",
                        ),
                        ratio,
                    ),
                    suggestion = t(
                        lang,
                        "Darken the text or lighten the background until the " +
                            "ratio reaches at least 4.5:1.",
                        "Oscurecé el texto o aclará el fondo hasta llegar a 4.5:1.",
                    ),
                )
            }
        }
        return issues
    }

    // -- color helpers (WCAG relative luminance) ----------------------------

    private val WHITE = intArrayOf(255, 255, 255)

    private fun bgOf(el: Element): IntArray? {
        var node: Element? = el
        while (node != null) {
            val c = styleColor(node.attr("style").lowercase(), "background-color")
            if (c != null) return c
            node = node.parent()
        }
        return null
    }

    private fun styleColor(style: String, prop: String): IntArray? {
        val m = Regex(Regex.escape(prop) + "\\s*:\\s*([^;]+)").find(style) ?: return null
        return parseColor(m.groupValues[1].trim())
    }

    /** Parses #rgb, #rrggbb and rgb(r, g, b). Null when unparseable. */
    fun parseColor(raw: String): IntArray? {
        val s = raw.trim().lowercase()
        if (s.startsWith("#")) {
            val h = s.drop(1)
            val full = when (h.length) {
                3 -> h.map { "$it$it" }.joinToString("")
                6 -> h
                else -> return null
            }
            if (!full.all { it in '0'..'9' || it in 'a'..'f' }) return null
            return intArrayOf(
                full.substring(0, 2).toInt(16),
                full.substring(2, 4).toInt(16),
                full.substring(4, 6).toInt(16),
            )
        }
        val m = Regex("rgba?\\(([^)]+)\\)").find(s) ?: return null
        val parts = m.groupValues[1].split(",").map { it.trim() }
        if (parts.size < 3) return null
        val nums = parts.take(3).map { it.removeSuffix("%").toDoubleOrNull() ?: return null }
        return intArrayOf(nums[0].toInt(), nums[1].toInt(), nums[2].toInt())
    }

    /** WCAG contrast ratio between two sRGB colors. */
    fun contrastRatio(a: IntArray, b: IntArray): Double {
        val l1 = luminance(a)
        val l2 = luminance(b)
        val (hi, lo) = if (l1 >= l2) l1 to l2 else l2 to l1
        return (hi + 0.05) / (lo + 0.05)
    }

    private fun luminance(rgb: IntArray): Double {
        fun lin(c: Int): Double {
            val v = c / 255.0
            return if (v <= 0.03928) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * lin(rgb[0]) + 0.7152 * lin(rgb[1]) + 0.0722 * lin(rgb[2])
    }

    private fun snippet(el: Element): String {
        val raw = el.outerHtml().replace(Regex("\\s+"), " ").trim()
        return if (raw.length <= 140) raw else raw.take(137) + "..."
    }
}
