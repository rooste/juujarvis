package com.juujarvis.service

import kotlin.test.Test
import kotlin.test.assertEquals

class ConfluenceMarkupTest {
    @Test
    fun `headings, lists, paragraphs and inline markers become storage format`() {
        val md = """
            # Trip plan
            We leave **Friday** at *noon*.
            Bring `snacks`.

            - tent
            - [map](https://example.com/a?b=1&c=2)
            1. pack
            2. drive
        """.trimIndent()
        assertEquals(
            "<h1>Trip plan</h1><p>We leave <strong>Friday</strong> at <em>noon</em>. Bring <code>snacks</code>.</p>" +
                "<ul><li>tent</li><li><a href=\"https://example.com/a?b=1&amp;c=2\">map</a></li></ul>" +
                "<ol><li>pack</li><li>drive</li></ol>",
            ConfluenceMarkup.toStorage(md)
        )
    }

    @Test
    fun `raw html, macros and script links are escaped`() {
        assertEquals(
            "<p>&lt;ac:structured-macro ac:name=&quot;html&quot;&gt; &lt;script&gt;x&lt;/script&gt; [x](javascript:alert(1))</p>",
            ConfluenceMarkup.toStorage("<ac:structured-macro ac:name=\"html\"> <script>x</script> [x](javascript:alert(1))")
        )
    }

    @Test
    fun `markdown tables become confluence tables`() {
        assertEquals(
            "<table><tbody><tr><th><p>Day</p></th><th><p>Plan</p></th></tr>" +
                "<tr><td><p>Jan 4</p></td><td><p><a href=\"https://www.nhl.com/stars\">Stars game</a></p></td></tr></tbody></table>",
            ConfluenceMarkup.toStorage("| Day | Plan |\n|---|---|\n| Jan 4 | [Stars game](https://www.nhl.com/stars) |")
        )
    }

    private val richPage = """<ac:layout><ac:layout-section ac:type="two_equal"><ac:layout-cell><h1 local-id="a">Plan</h1><p local-id="b" /></ac:layout-cell></ac:layout-section></ac:layout>""" +
        """<table data-layout="default"><tbody><tr><th><p><strong>Day</strong></p></th><th><p>Activity</p></th></tr>""" +
        """<tr><td><p>4.1.</p></td><td><p><a href="https://example.com/x">Stars</a> &auml;</p></td></tr></tbody></table>""" +
        """<ac:task-list><ac:task><ac:task-id>1</ac:task-id><ac:task-status>complete</ac:task-status><ac:task-body>Brisket</ac:task-body></ac:task></ac:task-list>""" +
        """<ac:structured-macro ac:name="toc"><ac:parameter ac:name="style">none</ac:parameter></ac:structured-macro>"""

    @Test
    fun `pages read as markdown keep tables, links and checklists`() {
        assertEquals(
            "# Plan\n\n| **Day** | Activity |\n| --- | --- |\n| 4.1. | [Stars](https://example.com/x) ä |\n\n- [x] Brisket\n\n[macro: toc]",
            ConfluenceMarkup.toMarkdown(richPage)
        )
        val structure = ConfluenceMarkup.structure(richPage)
        assertEquals(1, structure.tables)
        assertEquals(1, structure.layoutSections)
        assertEquals(listOf("toc"), structure.macros)
    }

    @Test
    fun `source edits may not add scripts, html macros or script links`() {
        assertEquals(null, ConfluenceMarkup.checkSource(richPage))
        assertEquals("<script> is not allowed", ConfluenceMarkup.checkSource("<p>x</p><script>alert(1)</script>"))
        assertEquals("the html macro is not allowed", ConfluenceMarkup.checkSource("<ac:structured-macro ac:name=\"html\"><ac:plain-text-body>x</ac:plain-text-body></ac:structured-macro>"))
        assertEquals("script or data links are not allowed", ConfluenceMarkup.checkSource("<a href=\" JavaScript:alert(1)\">x</a>"))
        assertEquals("event handler attributes are not allowed", ConfluenceMarkup.checkSource("<p onclick=\"x()\">x</p>"))
    }
}
