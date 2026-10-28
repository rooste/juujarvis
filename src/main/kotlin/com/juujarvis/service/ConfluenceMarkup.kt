package com.juujarvis.service

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import org.jsoup.parser.Parser

/**
 * Converts between the simple Markdown the model works with and Confluence storage format (XHTML).
 *
 * [toStorage]: headings (#–###), bullet and numbered lists, | pipe | tables |, paragraphs, **bold**, *italic*,
 * `code` and [text](https://…) links. Everything else is escaped text, so no raw HTML or macros get in.
 * [toMarkdown]: a readable view of a page, keeping tables and links.
 * [checkSource]: what may be written when the model edits storage format directly.
 */
object ConfluenceMarkup {
    private val heading = Regex("^(#{1,3})\\s+(.*)$")
    private val bullet = Regex("^[-*]\\s+(.*)$")
    private val numbered = Regex("^\\d+[.)]\\s+(.*)$")
    private val tableRow = Regex("^\\|(.*)\\|$")
    private val tableDivider = Regex("^\\|?\\s*:?-{2,}:?\\s*(\\|\\s*:?-{2,}:?\\s*)*\\|?$")

    fun toStorage(markdown: String): String {
        val out = StringBuilder()
        val paragraph = mutableListOf<String>()
        var list: String? = null   // "ul" or "ol" while inside a list
        val table = mutableListOf<List<String>>()

        fun closeParagraph() {
            if (paragraph.isNotEmpty()) out.append("<p>").append(paragraph.joinToString(" ") { inline(it) }).append("</p>")
            paragraph.clear()
        }
        fun closeList() {
            if (list != null) out.append("</$list>")
            list = null
        }
        fun closeTable() {
            if (table.isEmpty()) return
            out.append("<table><tbody>")
            table.forEachIndexed { i, row ->
                val cell = if (i == 0) "th" else "td"
                out.append("<tr>")
                row.forEach { out.append("<$cell><p>").append(inline(it)).append("</p></$cell>") }
                out.append("</tr>")
            }
            out.append("</tbody></table>")
            table.clear()
        }
        fun closeAll() { closeParagraph(); closeList(); closeTable() }
        fun listItem(tag: String, text: String) {
            closeParagraph(); closeTable()
            if (list != tag) { closeList(); out.append("<$tag>"); list = tag }
            out.append("<li>").append(inline(text)).append("</li>")
        }

        for (raw in markdown.replace("\r\n", "\n").lines()) {
            val line = raw.trim()
            val h = heading.matchEntire(line)
            val b = bullet.matchEntire(line)
            val n = numbered.matchEntire(line)
            val t = tableRow.matchEntire(line)
            when {
                t != null && tableDivider.matches(line) -> {}   // the |---|---| line under the header
                t != null -> {
                    closeParagraph(); closeList()
                    table.add(t.groupValues[1].split("|").map { it.trim() })
                }
                h != null -> {
                    closeAll()
                    val level = h.groupValues[1].length
                    out.append("<h$level>").append(inline(h.groupValues[2])).append("</h$level>")
                }
                b != null -> listItem("ul", b.groupValues[1])
                n != null -> listItem("ol", n.groupValues[1])
                line.isEmpty() -> closeAll()
                else -> { closeList(); closeTable(); paragraph.add(line) }
            }
        }
        closeAll()
        return out.toString()
    }

    private val link = Regex("\\[([^\\]]+)]\\((https?://[^\\s)]+)\\)")
    private val bold = Regex("\\*\\*(.+?)\\*\\*")
    private val italic = Regex("(?<![*\\w])\\*(?!\\s)(.+?)(?<!\\s)\\*(?![*\\w])")
    private val code = Regex("`([^`]+)`")

    /** Escapes the text, then turns the inline markers back into tags (their content is already escaped). */
    private fun inline(text: String): String {
        var s = escape(text)
        s = code.replace(s) { "<code>${it.groupValues[1]}</code>" }
        s = link.replace(s) { "<a href=\"${it.groupValues[2]}\">${it.groupValues[1]}</a>" }
        s = bold.replace(s) { "<strong>${it.groupValues[1]}</strong>" }
        s = italic.replace(s) { "<em>${it.groupValues[1]}</em>" }
        return s
    }

    private fun escape(text: String) = text
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    // ---- storage → Markdown (reading) ----

    /** What a page contains besides plain text; a Markdown rewrite would lose these. */
    data class Structure(val tables: Int, val layoutSections: Int, val macros: List<String>) {
        val isRich get() = tables > 0 || layoutSections > 0 || macros.isNotEmpty()
        override fun toString() = buildList {
            if (tables > 0) add("$tables table(s)")
            if (layoutSections > 0) add("$layoutSections layout section(s)")
            if (macros.isNotEmpty()) add("macros: ${macros.distinct().joinToString(", ")}")
        }.joinToString(", ").ifEmpty { "plain content" }
    }

    /** Storage format is XML with Confluence namespaces and HTML entities; the XML parser keeps <p/> empty. */
    private fun parse(storage: String): Element =
        Jsoup.parse("<root>$storage</root>", "", Parser.xmlParser()).selectFirst("root")!!

    fun structure(storage: String): Structure {
        val doc = parse(storage)
        return Structure(
            tables = doc.select("table").size,
            layoutSections = doc.getElementsByTag("ac:layout-section").size,
            macros = doc.getElementsByTag("ac:structured-macro").map { it.attr("ac:name") }
        )
    }

    fun toMarkdown(storage: String): String {
        val out = StringBuilder()
        blocks(parse(storage), out)
        return out.toString().replace(Regex("\n{3,}"), "\n\n").trim()
    }

    private fun blocks(parent: Element, out: StringBuilder) {
        for (node in parent.childNodes()) {
            when {
                node is TextNode -> if (!node.isBlank) out.append(node.text().trim()).append("\n\n")
                node !is Element -> {}
                node.tagName().matches(Regex("h[1-6]")) ->
                    out.append("#".repeat(node.tagName()[1].digitToInt())).append(' ').append(inlineText(node)).append("\n\n")
                node.tagName() == "p" -> inlineText(node).takeIf { it.isNotBlank() }?.let { out.append(it).append("\n\n") }
                node.tagName() == "ul" || node.tagName() == "ol" -> {
                    node.children().toList().filter { it.tagName() == "li" }.forEachIndexed { i, li ->
                        out.append(if (node.tagName() == "ol") "${i + 1}. " else "- ").append(inlineText(li)).append('\n')
                    }
                    out.append('\n')
                }
                node.tagName() == "table" -> table(node, out)
                node.tagName() == "ac:task-list" -> {
                    node.children().toList().filter { it.tagName() == "ac:task" }.forEach { out.append(task(it)).append('\n') }
                    out.append('\n')
                }
                node.tagName() in SKIPPED -> {}
                node.tagName() == "ac:structured-macro" -> {
                    out.append("[macro: ${node.attr("ac:name")}]\n\n")
                    node.getElementsByTag("ac:rich-text-body").firstOrNull()?.let { blocks(it, out) }
                }
                else -> blocks(node, out)   // layouts, cells, divs: just their content
            }
        }
    }

    /** Macro settings, task ids and editor placeholders are not content. */
    private val SKIPPED = setOf("ac:parameter", "ac:task-id", "ac:task-status", "ac:placeholder", "ac:emoticon")

    private fun task(task: Element): String {
        val done = task.getElementsByTag("ac:task-status").firstOrNull()?.text() == "complete"
        val body = task.getElementsByTag("ac:task-body").firstOrNull()?.let { inlineText(it) } ?: ""
        return (if (done) "- [x] " else "- [ ] ") + body
    }

    private fun table(table: Element, out: StringBuilder) {
        val rows = table.select("tr").toList().filter { it.closest("table") == table }
        rows.forEachIndexed { i, tr ->
            val cells = tr.children().toList().filter { it.tagName() == "td" || it.tagName() == "th" }
            out.append("| ").append(cells.joinToString(" | ") { cellText(it) }).append(" |\n")
            if (i == 0) out.append("|").append(" --- |".repeat(cells.size.coerceAtLeast(1))).append('\n')
        }
        out.append('\n')
    }

    private fun cellText(cell: Element): String {
        val parts = cell.children().toList().filter { it.tagName() in setOf("p", "li", "ul", "ol") || it.tagName().matches(Regex("h[1-6]")) }
        val text = if (parts.isEmpty()) inlineText(cell) else parts.joinToString("<br>") {
            if (it.tagName() == "ul" || it.tagName() == "ol") it.children().joinToString("<br>") { li -> "• " + inlineText(li) } else inlineText(it)
        }
        return text.replace("|", "\\|").ifBlank { " " }
    }

    private fun inlineText(el: Element): String = buildString { inlineNodes(el.childNodes(), this) }.replace(Regex("\\s+"), " ").trim()

    private fun inlineNodes(nodes: List<Node>, out: StringBuilder) {
        for (node in nodes) {
            when {
                node is TextNode -> out.append(node.text())
                node !is Element -> {}
                node.tagName() == "a" -> out.append("[").append(inlineText(node)).append("](").append(node.attr("href")).append(")")
                node.tagName() == "ac:link" -> {
                    val title = node.getElementsByTag("ri:page").firstOrNull()?.attr("ri:content-title")
                    val text = node.getElementsByTag("ac:link-body").firstOrNull()?.let { inlineText(it) }
                        ?: node.getElementsByTag("ac:plain-text-link-body").firstOrNull()?.text()
                        ?: title ?: "link"
                    out.append("[").append(text).append("](Confluence page: ").append(title ?: "?").append(")")
                }
                node.tagName() == "strong" || node.tagName() == "b" -> out.append("**").append(inlineText(node)).append("**")
                node.tagName() == "em" || node.tagName() == "i" -> out.append("*").append(inlineText(node)).append("*")
                node.tagName() == "code" -> out.append("`").append(node.text()).append("`")
                node.tagName() in SKIPPED -> {}
                node.tagName() == "ac:structured-macro" -> out.append("[macro: ").append(node.attr("ac:name")).append("]")
                node.tagName() == "ac:task-list" -> node.children().forEach { out.append(" ").append(task(it)) }
                node.tagName() == "br" -> out.append(' ')
                node.tagName() == "ul" || node.tagName() == "ol" -> node.children().forEach { out.append(" • ").append(inlineText(it)) }
                node.tagName() == "p" -> { out.append(' '); inlineNodes(node.childNodes(), out); out.append(' ') }
                else -> inlineNodes(node.childNodes(), out)
            }
        }
    }

    // ---- storage written by the model ----

    private val forbiddenTags = setOf("script", "iframe", "object", "embed", "style", "form", "input", "frame", "frameset", "base", "meta", "link")
    private val forbiddenMacros = setOf("html", "html-include", "iframe", "include-html", "widget")

    /** Returns why [storage] may not be written, or null if it is fine. Confluence checks well-formedness itself. */
    fun checkSource(storage: String): String? {
        for (el in parse(storage).getAllElements()) {
            val tag = el.tagName()
            if (tag in forbiddenTags) return "<$tag> is not allowed"
            if (tag == "ac:structured-macro" && el.attr("ac:name").lowercase() in forbiddenMacros)
                return "the ${el.attr("ac:name")} macro is not allowed"
            for (attr in el.attributes()) {
                val key = attr.key.lowercase()
                val value = attr.value.trim().lowercase()
                if (key.startsWith("on")) return "event handler attributes are not allowed"
                if ((key == "href" || key == "src" || key.endsWith(":value")) &&
                    (value.startsWith("javascript:") || value.startsWith("data:") || value.startsWith("vbscript:")))
                    return "script or data links are not allowed"
            }
        }
        return null
    }
}
