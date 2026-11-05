package ug.kuuma.casefile.export

import java.io.ByteArrayOutputStream

/**
 * A minimal text-only PDF writer (A4, Helvetica, WinAnsi). Pure Kotlin, so case files
 * are built the same way on Android 5 and in JVM tests, with no PDF library. Characters
 * outside Latin-1 are printed as '?'; the JSON bundle keeps the exact text.
 */
class PdfText(private val wrapAt: Int = 95) {
    private data class Line(val text: String, val size: Int, val bold: Boolean)

    private val lines = ArrayList<Line>()

    fun heading(text: String) = lines.add(Line(text, 13, true))
    fun line(text: String) = lines.add(Line(text, 9, false))
    fun gap() = lines.add(Line("", 6, false))

    fun para(text: String) {
        for (raw in text.split('\n')) {
            var rest = raw
            while (rest.length > wrapAt) {
                val cut = rest.lastIndexOf(' ', wrapAt).takeIf { it > wrapAt / 2 } ?: wrapAt
                lines.add(Line(rest.substring(0, cut), 9, false))
                rest = rest.substring(cut).trimStart()
            }
            lines.add(Line(rest, 9, false))
        }
    }

    fun toPdf(): ByteArray {
        // Lay lines out on A4 pages (595 x 842 pt), 40 pt margins.
        val pages = ArrayList<List<Pair<Line, Int>>>()
        var current = ArrayList<Pair<Line, Int>>()
        var y = 800
        for (l in lines) {
            val leading = l.size + 4
            if (y - leading < 40) {
                pages += current
                current = ArrayList()
                y = 800
            }
            y -= leading
            current += l to y
        }
        pages += current

        val objects = ArrayList<ByteArray>()
        fun add(body: String) = objects.add(body.toByteArray(Charsets.ISO_8859_1)).let { objects.size }

        val catalog = add("<< /Type /Catalog /Pages 2 0 R >>")
        val pagesIndex = add("") // patched below
        val font = add("<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding /WinAnsiEncoding >>")
        val bold = add("<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica-Bold /Encoding /WinAnsiEncoding >>")
        val pageRefs = ArrayList<Int>()
        pages.forEachIndexed { n, page ->
            val stream = StringBuilder()
            for ((l, ly) in page) {
                if (l.text.isEmpty()) continue
                stream.append(
                    "BT /"
                ).append(if (l.bold) "F2" else "F1").append(' ').append(l.size).append(" Tf 40 ").append(ly)
                    .append(" Td (").append(escape(l.text)).append(") Tj ET\n")
            }
            stream.append("BT /F1 8 Tf 500 20 Td (Page ${n + 1} of ${pages.size}) Tj ET\n")
            val content = add("<< /Length ${stream.length} >>\nstream\n$stream\nendstream")
            pageRefs += add(
                "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] /Contents $content 0 R " +
                    "/Resources << /Font << /F1 $font 0 R /F2 $bold 0 R >> >> >>"
            )
        }
        objects[pagesIndex - 1] =
            "<< /Type /Pages /Kids [${pageRefs.joinToString(" ") { "$it 0 R" }}] /Count ${pageRefs.size} >>"
                .toByteArray(Charsets.ISO_8859_1)

        val out = ByteArrayOutputStream()
        out.write("%PDF-1.4\n%âãÏÓ\n".toByteArray(Charsets.ISO_8859_1))
        val offsets = ArrayList<Int>()
        objects.forEachIndexed { i, body ->
            offsets += out.size()
            out.write("${i + 1} 0 obj\n".toByteArray(Charsets.ISO_8859_1))
            out.write(body)
            out.write("\nendobj\n".toByteArray(Charsets.ISO_8859_1))
        }
        val xref = out.size()
        val trailer = StringBuilder("xref\n0 ${objects.size + 1}\n0000000000 65535 f \n")
        offsets.forEach { trailer.append(String.format("%010d 00000 n \n", it)) }
        trailer.append("trailer\n<< /Size ${objects.size + 1} /Root $catalog 0 R >>\nstartxref\n$xref\n%%EOF\n")
        out.write(trailer.toString().toByteArray(Charsets.ISO_8859_1))
        return out.toByteArray()
    }

    private fun escape(s: String): String = buildString {
        for (c in s) {
            when {
                c == '(' || c == ')' || c == '\\' -> append('\\').append(c)
                c.code in 32..126 || c.code in 160..255 -> append(c)
                c == '·' -> append('·')
                else -> append('?')
            }
        }
    }
}
