// SPDX-License-Identifier: GPL-3.0-or-later

package com.sightline.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Local unit tests for the deterministic audit (run in Android Studio). */
class AccessibilityAnalyzerTest {

    private fun asset(name: String): String =
        javaClass.classLoader!!.getResourceAsStream(name)!!.bufferedReader().readText()

    @Test
    fun `demo page contains the eight planted findings`() {
        val issues = AccessibilityAnalyzer.analyze(asset("demo.html"))
        val types = issues.map { it.type }.toSet()
        assertTrue(types.contains("IMG_MISSING_ALT"))
        assertTrue(types.contains("INPUT_MISSING_LABEL"))
        assertTrue(types.contains("BUTTON_NO_NAME"))
        assertTrue(types.contains("LINK_NO_NAME"))
        assertTrue(types.contains("LOW_CONTRAST"))
        assertTrue(types.contains("HEADING_ORDER"))
        assertTrue(types.contains("EMPTY_HEADING"))
        assertTrue(types.contains("MISSING_VIEWPORT"))
    }

    @Test
    fun `accessible page reports almost nothing`() {
        val html = """
            <!DOCTYPE html><html lang="en"><head><title>OK</title>
            <meta name="viewport" content="width=device-width, initial-scale=1">
            </head><body>
            <h1>Hi</h1><h2>Sub</h2>
            <img src="a.jpg" alt="A cat sleeping">
            <label for="n">Name</label><input id="n" type="text">
            <button aria-label="Close">x</button>
            <a href="/x">Offers</a>
            <p style="color:#000000;">Dark text</p>
            </body></html>
        """.trimIndent()
        val types = AccessibilityAnalyzer.analyze(html).map { it.type }.toSet()
        assertTrue(types.intersect(setOf("IMG_MISSING_ALT", "INPUT_MISSING_LABEL", "BUTTON_NO_NAME")).isEmpty())
        assertTrue(!types.contains("EMPTY_HEADING"))
        assertTrue(!types.contains("MISSING_VIEWPORT"))
    }

    @Test
    fun `black on white contrast is 21 to 1`() {
        val ratio = AccessibilityAnalyzer.contrastRatio(
            AccessibilityAnalyzer.parseColor("#000000")!!,
            AccessibilityAnalyzer.parseColor("#ffffff")!!,
        )
        assertEquals(21.0, ratio, 0.05)
    }

    @Test
    fun `grey 777 on white is just under the threshold`() {
        val ratio = AccessibilityAnalyzer.contrastRatio(
            AccessibilityAnalyzer.parseColor("#777777")!!,
            AccessibilityAnalyzer.parseColor("#ffffff")!!,
        )
        assertTrue(ratio < AccessibilityAnalyzer.CONTRAST_THRESHOLD)
        assertTrue(ratio > 4.0)
    }

    @Test
    fun `contrast finding shows the real number in both languages`() {
        for (lang in listOf("en", "es")) {
            val issues = AccessibilityAnalyzer.analyze(asset("demo.html"), lang)
            val low = issues.first { it.type == "LOW_CONTRAST" }
            assertTrue(low.description.contains("4.48"))
            assertTrue(!low.description.contains("%"))
        }
    }

    @Test
    fun `score penalises errors more than warnings`() {
        val withError = listOf(
            Issue("IMG_MISSING_ALT", Severity.ERROR, "e", "d", "s"),
        )
        val withWarning = listOf(
            Issue("LOW_CONTRAST", Severity.WARNING, "e", "d", "s"),
        )
        assertTrue(AccessibilityAnalyzer.score(withError) < AccessibilityAnalyzer.score(withWarning))
    }

    @Test
    fun `meta payload targets muse spark`() {
        val client = MetaAiClient(apiKey = "DUMMY")
        val issue = Issue("IMG_MISSING_ALT", Severity.ERROR, "<img src=\"x\">", "d", "s")
        val payload = client.buildPayload(issue, "<html></html>")
        assertTrue(payload.contains("muse-spark-1.3-contributor"))
        assertTrue(payload.contains("IMG_MISSING_ALT"))
    }

    @Test
    fun `meta response content is extracted`() {
        val client = MetaAiClient(apiKey = "DUMMY")
        val json = """{"choices": [{"message": {"content": "  Add alt text.  "}}]}"""
        assertEquals("Add alt text.", client.extractContent(json))
    }

    @Test
    fun `null meta content extracts to empty string`() {
        val client = MetaAiClient(apiKey = "DUMMY")
        val json = """{"choices": [{"message": {"content": null}}]}"""
        assertEquals("", client.extractContent(json))
    }

    @Test
    fun `key validation failures map to the right message`() {
        assertEquals("t_keyInvalid", MetaAiClient.validationFailureKey("Meta API error 401"))
        assertEquals("t_keyInvalid", MetaAiClient.validationFailureKey("Meta API error 400"))
        assertEquals("t_keyInvalid", MetaAiClient.validationFailureKey("Meta API error 403"))
        assertEquals("aiNetFail", MetaAiClient.validationFailureKey("Meta API error 500"))
        assertEquals("aiNetFail", MetaAiClient.validationFailureKey("Unable to resolve host"))
        assertEquals("aiNetFail", MetaAiClient.validationFailureKey(""))
    }

    @Test
    fun `history keeps newest audits first and caps the list`() {
        assertTrue(History.parse(null).isEmpty())
        var entries = emptyList<History.Entry>()
        for (i in 1..25) {
            entries = History.add(entries, History.Entry(i.toLong(), "https://p$i.test", i, i))
        }
        assertEquals(20, entries.size)
        assertEquals("https://p25.test", entries.first().source)
        assertEquals("https://p6.test", entries.last().source)
        assertEquals(entries, History.parse(History.format(entries)))
    }

    @Test
    fun `history skips malformed lines and sanitizes sources`() {
        val entries = History.parse("badline\n12/09 10:00|x|y|z\n100|https://ok.test|39|8")
        assertEquals(1, entries.size)
        assertEquals(History.Entry(100L, "https://ok.test", 39, 8), entries[0])
        val added = History.add(emptyList(), History.Entry(1L, "a|b", 10, 1))
        assertEquals("a/b", added[0].source)
    }

    @Test
    fun `history strings exist in both languages`() {
        for (key in listOf("historyTitle", "historyEmpty")) {
            assertTrue(Strings.get("es", key).isNotBlank())
            assertTrue(Strings.get("en", key).isNotBlank())
        }
    }

    @Test
    fun `empty key means no AI available`() {
        assertEquals(false, MetaAiClient(apiKey = "").hasKey())
        assertEquals(true, MetaAiClient(apiKey = "DUMMY").hasKey())
    }

    @Test
    fun `spanish covers every english key`() {
        for (key in Strings.keys()) {
            assertTrue("missing es string: $key", Strings.has("es", key))
        }
    }

    @Test
    fun `spanish ui never shows the english word fix`() {
        for (key in Strings.keys()) {
            val value = Strings.get("es", key)
            assertFalse("es string uses fix: $key", value.contains("fix", ignoreCase = true))
        }
        assertTrue(Strings.get("es", "fix").contains("IA"))
    }

    @Test
    fun `unknown string key falls back to the key`() {
        assertEquals("nope.missing", Strings.get("en", "nope.missing"))
    }

    @Test
    fun `spanish findings speak spanish`() {
        val issues = AccessibilityAnalyzer.analyze(asset("demo.html"), "es")
        val img = issues.first { it.type == "IMG_MISSING_ALT" }
        assertTrue(img.description.contains("lector de pantalla"))
        assertTrue(img.suggestion.contains("Agregá"))
    }

    @Test
    fun `english stays the default finding language`() {
        val issues = AccessibilityAnalyzer.analyze(asset("demo.html"))
        assertTrue(issues.any {
            it.type == "IMG_MISSING_ALT" && it.description.contains("screen-reader")
        })
    }

    @Test
    fun `ai payload asks in the user language`() {
        val client = MetaAiClient(apiKey = "DUMMY")
        val issue = Issue("IMG_MISSING_ALT", Severity.ERROR, "<img>", "d", "s")
        assertTrue(client.buildPayload(issue, "x", "es").contains("Reply in Spanish"))
        assertTrue(client.buildPayload(issue, "x", "en").contains("Reply in English"))
    }

    @Test
    fun `analysis payload carries model, findings and language`() {
        val client = MetaAiClient(apiKey = "DUMMY")
        val issues = listOf(
            Issue("IMG_MISSING_ALT", Severity.ERROR, "<img>", "d", "s", marker = 1),
            Issue("LOW_CONTRAST", Severity.WARNING, "<p>", "d", "s", marker = 2),
        )
        val es = client.buildAnalysisPayload("https://demo.test", issues, "es")
        assertTrue(es.contains("muse-spark-1.3-contributor"))
        assertTrue(es.contains("IMG_MISSING_ALT"))
        assertTrue(es.contains("2 total"))
        assertTrue(es.contains("Reply in Spanish"))
        assertTrue(client.buildAnalysisPayload("https://demo.test", issues, "en").contains("Reply in English"))
    }

    @Test
    fun `new strings exist in both languages`() {
        for (key in listOf("aiAnalyze", "verdictTitle", "copy", "copied")) {
            assertTrue(Strings.get("es", key).isNotBlank())
            assertTrue(Strings.get("en", key).isNotBlank())
        }
    }

    @Test
    fun `markers number every finding in order`() {
        val issues = AccessibilityAnalyzer.analyze(asset("demo.html"))
        assertEquals((1..issues.size).toList(), issues.map { it.marker })
    }

    @Test
    fun `highlight tags findings with matching badges`() {
        val marked = AccessibilityAnalyzer.highlight(asset("demo.html"))
        assertTrue(marked.contains(".slmark"))
        // 8 findings, 1 head-level (viewport): 7 badges expected.
        val badges = Regex("data-sl=\"[0-9,]+\"").findAll(marked).toList()
        assertEquals(7, badges.size)
        assertTrue(badges.any { it.value.contains("1") })
    }

    @Test
    fun `highlight unsticks fixed banners but keeps badge anchors`() {
        val marked = AccessibilityAnalyzer.highlight(asset("demo.html"))
        // Marked elements stay anchored no matter the page CSS.
        assertTrue(marked.contains("position:relative !important"))
        // Everything else goes static: no floating/following banners.
        assertTrue(marked.contains("body :not(.slmark){position:static !important;}"))
    }

    @Test
    fun `fix plan carries verdict, findings and cached ai fixes`() {
        val issues = listOf(
            Issue("IMG_MISSING_ALT", Severity.ERROR, "<img>", "Sin alt.", "Agregá alt.", marker = 2),
            Issue("LOW_CONTRAST", Severity.WARNING, "<p>", "Poco contraste.", "Oscurecé.", marker = 8),
        )
        val plan = Report.buildPlan("es", "demo", 39, issues, "Hay que arreglar.", mapOf(2 to "<img alt=\"x\">"))
        assertTrue(plan.contains("Plan de arreglos"))
        assertTrue(plan.contains("demo"))
        assertTrue(plan.contains("39/100"))
        assertTrue(plan.contains("Hay que arreglar."))
        assertTrue(plan.contains("#2 · Error · IMG_MISSING_ALT"))
        assertTrue(plan.contains("Arreglo base: Agregá alt."))
        assertTrue(plan.contains("<img alt=\"x\">"))
        // Unrequested AI fixes point back to their card instead of calling the API.
        assertTrue(plan.contains("tarjeta #8"))
        assertTrue(Report.buildPlan("en", "demo", 39, issues, "", emptyMap()).contains("AI verdict:"))
    }

    @Test
    fun `fix plan with no issues stays short`() {
        val plan = Report.buildPlan("es", "demo", 100, emptyList(), "", emptyMap())
        assertTrue(plan.contains("100/100"))
        assertTrue(plan.contains("Sin problemas"))
    }

    @Test
    fun `issues survive a serialization round trip`() {
        val issue = Issue("IMG_MISSING_ALT", Severity.ERROR, "<img>", "d", "s", marker = 3)
        val bytes = java.io.ByteArrayOutputStream().use { bos ->
            java.io.ObjectOutputStream(bos).use { it.writeObject(issue) }
            bos.toByteArray()
        }
        val back = java.io.ObjectInputStream(bytes.inputStream()).use { it.readObject() as Issue }
        assertEquals(issue, back)
    }

    @Test
    fun `signed delta formats before-after`() {
        assertEquals("+12", Report.signed(12))
        assertEquals("-3", Report.signed(-3))
        assertEquals("0", Report.signed(0))
    }

    @Test
    fun `plan html and before-after strings exist in both languages`() {
        for (key in listOf(
            "planBtn", "planCopied", "planHead", "planVerdict", "planAskAi", "planAiMissing",
            "htmlBtn", "htmlLabel", "htmlHint", "htmlAudit", "htmlEmpty", "htmlSource",
            "beforeAfter",
            "menuTitle", "menuUrlT", "menuUrlD", "menuDemoT", "menuDemoD",
            "menuHtmlT", "menuHtmlD", "menuBack",
            "findingsFmt", "aiEmpty", "aiNetFail",
        )) {
            assertTrue("blank es: $key", Strings.get("es", key).isNotBlank())
            assertTrue("blank en: $key", Strings.get("en", key).isNotBlank())
        }
    }

    @Test
    fun `report contains source score and types`() {
        val issues = listOf(Issue("IMG_MISSING_ALT", Severity.ERROR, "e", "Image has no alt.", "s"))
        val text = Report.build("en", "demo", 88, issues)
        assertTrue(text.contains("demo"))
        assertTrue(text.contains("88"))
        assertTrue(text.contains("IMG_MISSING_ALT"))
    }

    @Test
    fun `spanish report uses spanish header`() {
        val text = Report.build("es", "demo", 88, emptyList())
        assertTrue(text.contains("Auditoría"))
    }

    @Test
    fun `help strings exist in both languages`() {
        val keys = mutableListOf("helpBtn", "helpTitle", "helpSteps", "helpFaq", "helpChecks")
        for (i in 1..5) keys.add("hstep$i")
        for (i in 1..6) {
            keys.add("hq$i")
            keys.add("ha$i")
            keys.add("hchk${i}t")
            keys.add("hchk${i}d")
        }
        for (key in keys) {
            assertTrue("blank es: $key", Strings.get("es", key).isNotBlank())
            assertTrue("blank en: $key", Strings.get("en", key).isNotBlank())
        }
    }

    @Test
    fun `about strings exist in both languages`() {
        val keys = mutableListOf("aboutTitle", "aboutMission", "aboutObjT", "aboutHowT", "appVersion", "aboutLicense")
        for (i in 1..4) keys.add("aboutObj$i")
        for (i in 1..3) keys.add("aboutHow$i")
        for (key in keys) {
            assertTrue("blank es: $key", Strings.get("es", key).isNotBlank())
            assertTrue("blank en: $key", Strings.get("en", key).isNotBlank())
        }
    }

    @Test
    fun `key management strings exist in both languages`() {
        for (key in listOf("forgetKey", "t_keyForgot", "keyStatusSet", "keyHelp")) {
            assertTrue("blank es: $key", Strings.get("es", key).isNotBlank())
            assertTrue("blank en: $key", Strings.get("en", key).isNotBlank())
        }
    }

    @Test
    fun `delta line renders in each language from the same numbers`() {
        assertEquals("Antes: 39 → Ahora: 41 (+2)", Report.deltaLine("es", 39, 41))
        assertEquals("Before: 39 → Now: 41 (+2)", Report.deltaLine("en", 39, 41))
        assertEquals("Before: 50 → Now: 47 (-3)", Report.deltaLine("en", 50, 47))
    }
}
