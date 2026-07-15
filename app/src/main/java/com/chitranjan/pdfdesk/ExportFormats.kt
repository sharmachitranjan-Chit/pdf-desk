package com.chitranjan.pdfdesk

import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Builds minimal, valid OOXML packages (docx/xlsx/pptx) from the text
 * extracted out of the working PDF. These carry CONTENT (lines; columns
 * where the page had table-like gaps), not the PDF's visual layout —
 * true layout conversion needs a commercial engine.
 */
object ExportFormats {

    private fun esc(s: String): String = s
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;").replace("'", "&apos;")

    private fun zip(out: File, entries: List<Pair<String, String>>) {
        ZipOutputStream(FileOutputStream(out)).use { z ->
            entries.forEach { (name, content) ->
                z.putNextEntry(ZipEntry(name))
                z.write(content.toByteArray(Charsets.UTF_8))
                z.closeEntry()
            }
        }
    }

    private const val XMLH = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
    private const val RELNS = "http://schemas.openxmlformats.org/package/2006/relationships"
    private const val ODREL = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"

    // ---------------- Word ----------------

    fun buildDocx(pages: List<List<List<String>>>, out: File) {
        val body = StringBuilder()
        pages.forEachIndexed { i, lines ->
            body.append("<w:p><w:r><w:rPr><w:b/></w:rPr><w:t xml:space=\"preserve\">Page ${i + 1}</w:t></w:r></w:p>")
            if (lines.isEmpty()) {
                body.append("<w:p><w:r><w:t xml:space=\"preserve\">(no extractable text on this page)</w:t></w:r></w:p>")
            } else lines.forEach { cells ->
                val text = cells.joinToString("    ")
                body.append("<w:p><w:r><w:t xml:space=\"preserve\">${esc(text)}</w:t></w:r></w:p>")
            }
            body.append("<w:p/>")
        }
        val document = XMLH +
            "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\">" +
            "<w:body>$body<w:sectPr/></w:body></w:document>"
        val types = XMLH + "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">" +
            "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>" +
            "<Default Extension=\"xml\" ContentType=\"application/xml\"/>" +
            "<Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/></Types>"
        val rels = XMLH + "<Relationships xmlns=\"$RELNS\">" +
            "<Relationship Id=\"rId1\" Type=\"$ODREL/officeDocument\" Target=\"word/document.xml\"/></Relationships>"
        zip(out, listOf("[Content_Types].xml" to types, "_rels/.rels" to rels, "word/document.xml" to document))
    }

    // ---------------- Excel ----------------

    fun buildXlsx(pages: List<List<List<String>>>, out: File) {
        val rows = StringBuilder()
        var r = 1
        fun row(cells: List<String>) {
            rows.append("<row r=\"$r\">")
            cells.forEach { c ->
                rows.append("<c t=\"inlineStr\"><is><t xml:space=\"preserve\">${esc(c)}</t></is></c>")
            }
            rows.append("</row>"); r++
        }
        pages.forEachIndexed { i, lines ->
            row(listOf("Page ${i + 1}"))
            if (lines.isEmpty()) row(listOf("(no extractable text)"))
            else lines.forEach { row(it) }
            row(emptyList())
        }
        val sheet = XMLH + "<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">" +
            "<sheetData>$rows</sheetData></worksheet>"
        val workbook = XMLH + "<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" " +
            "xmlns:r=\"$ODREL\"><sheets><sheet name=\"Extracted\" sheetId=\"1\" r:id=\"rId1\"/></sheets></workbook>"
        val wbRels = XMLH + "<Relationships xmlns=\"$RELNS\">" +
            "<Relationship Id=\"rId1\" Type=\"$ODREL/worksheet\" Target=\"worksheets/sheet1.xml\"/></Relationships>"
        val types = XMLH + "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">" +
            "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>" +
            "<Default Extension=\"xml\" ContentType=\"application/xml\"/>" +
            "<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>" +
            "<Override PartName=\"/xl/worksheets/sheet1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/></Types>"
        val rels = XMLH + "<Relationships xmlns=\"$RELNS\">" +
            "<Relationship Id=\"rId1\" Type=\"$ODREL/officeDocument\" Target=\"xl/workbook.xml\"/></Relationships>"
        zip(out, listOf(
            "[Content_Types].xml" to types,
            "_rels/.rels" to rels,
            "xl/workbook.xml" to workbook,
            "xl/_rels/workbook.xml.rels" to wbRels,
            "xl/worksheets/sheet1.xml" to sheet
        ))
    }

    // ---------------- PowerPoint ----------------

    private const val A = "http://schemas.openxmlformats.org/drawingml/2006/main"
    private const val P = "http://schemas.openxmlformats.org/presentationml/2006/main"

    private val EMPTY_TREE =
        "<p:spTree><p:nvGrpSpPr><p:cNvPr id=\"1\" name=\"\"/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr><p:grpSpPr/></p:spTree>"

    private val THEME = XMLH +
        "<a:theme xmlns:a=\"$A\" name=\"Office\"><a:themeElements>" +
        "<a:clrScheme name=\"Office\">" +
        "<a:dk1><a:sysClr val=\"windowText\" lastClr=\"000000\"/></a:dk1>" +
        "<a:lt1><a:sysClr val=\"window\" lastClr=\"FFFFFF\"/></a:lt1>" +
        "<a:dk2><a:srgbClr val=\"44546A\"/></a:dk2><a:lt2><a:srgbClr val=\"E7E6E6\"/></a:lt2>" +
        "<a:accent1><a:srgbClr val=\"4472C4\"/></a:accent1><a:accent2><a:srgbClr val=\"ED7D31\"/></a:accent2>" +
        "<a:accent3><a:srgbClr val=\"A5A5A5\"/></a:accent3><a:accent4><a:srgbClr val=\"FFC000\"/></a:accent4>" +
        "<a:accent5><a:srgbClr val=\"5B9BD5\"/></a:accent5><a:accent6><a:srgbClr val=\"70AD47\"/></a:accent6>" +
        "<a:hlink><a:srgbClr val=\"0563C1\"/></a:hlink><a:folHlink><a:srgbClr val=\"954F72\"/></a:folHlink>" +
        "</a:clrScheme>" +
        "<a:fontScheme name=\"Office\">" +
        "<a:majorFont><a:latin typeface=\"Calibri Light\"/><a:ea typeface=\"\"/><a:cs typeface=\"\"/></a:majorFont>" +
        "<a:minorFont><a:latin typeface=\"Calibri\"/><a:ea typeface=\"\"/><a:cs typeface=\"\"/></a:minorFont>" +
        "</a:fontScheme>" +
        "<a:fmtScheme name=\"Office\">" +
        "<a:fillStyleLst><a:solidFill><a:schemeClr val=\"phClr\"/></a:solidFill><a:solidFill><a:schemeClr val=\"phClr\"/></a:solidFill><a:solidFill><a:schemeClr val=\"phClr\"/></a:solidFill></a:fillStyleLst>" +
        "<a:lnStyleLst><a:ln><a:solidFill><a:schemeClr val=\"phClr\"/></a:solidFill></a:ln><a:ln><a:solidFill><a:schemeClr val=\"phClr\"/></a:solidFill></a:ln><a:ln><a:solidFill><a:schemeClr val=\"phClr\"/></a:solidFill></a:ln></a:lnStyleLst>" +
        "<a:effectStyleLst><a:effectStyle><a:effectLst/></a:effectStyle><a:effectStyle><a:effectLst/></a:effectStyle><a:effectStyle><a:effectLst/></a:effectStyle></a:effectStyleLst>" +
        "<a:bgFillStyleLst><a:solidFill><a:schemeClr val=\"phClr\"/></a:solidFill><a:solidFill><a:schemeClr val=\"phClr\"/></a:solidFill><a:solidFill><a:schemeClr val=\"phClr\"/></a:solidFill></a:bgFillStyleLst>" +
        "</a:fmtScheme></a:themeElements></a:theme>"

    fun buildPptx(pages: List<List<List<String>>>, out: File) {
        val entries = ArrayList<Pair<String, String>>()
        val n = pages.size.coerceAtLeast(1)

        val typeOverrides = StringBuilder()
        val sldIdLst = StringBuilder()
        val presRels = StringBuilder()
        presRels.append("<Relationship Id=\"rId1\" Type=\"$ODREL/slideMaster\" Target=\"slideMasters/slideMaster1.xml\"/>")

        for (k in 1..n) {
            val lines = pages.getOrNull(k - 1) ?: emptyList()
            val paras = StringBuilder()
            paras.append("<a:p><a:r><a:rPr lang=\"en-US\" sz=\"2000\" b=\"1\"/><a:t>Page $k</a:t></a:r></a:p>")
            val flat = if (lines.isEmpty()) listOf(listOf("(no extractable text)")) else lines
            flat.take(18).forEach { cells ->
                val t = cells.joinToString("   ").take(140)
                paras.append("<a:p><a:r><a:rPr lang=\"en-US\" sz=\"1200\"/><a:t>${esc(t)}</a:t></a:r></a:p>")
            }
            val slide = XMLH + "<p:sld xmlns:a=\"$A\" xmlns:p=\"$P\"><p:cSld><p:spTree>" +
                "<p:nvGrpSpPr><p:cNvPr id=\"1\" name=\"\"/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr><p:grpSpPr/>" +
                "<p:sp><p:nvSpPr><p:cNvPr id=\"2\" name=\"Text\"/><p:cNvSpPr/><p:nvPr/></p:nvSpPr>" +
                "<p:spPr><a:xfrm><a:off x=\"457200\" y=\"457200\"/><a:ext cx=\"8229600\" cy=\"5943600\"/></a:xfrm>" +
                "<a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom></p:spPr>" +
                "<p:txBody><a:bodyPr/><a:lstStyle/>$paras</p:txBody></p:sp>" +
                "</p:spTree></p:cSld></p:sld>"
            entries.add("ppt/slides/slide$k.xml" to slide)
            entries.add("ppt/slides/_rels/slide$k.xml.rels" to (XMLH +
                "<Relationships xmlns=\"$RELNS\"><Relationship Id=\"rId1\" Type=\"$ODREL/slideLayout\" Target=\"../slideLayouts/slideLayout1.xml\"/></Relationships>"))
            typeOverrides.append("<Override PartName=\"/ppt/slides/slide$k.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.presentationml.slide+xml\"/>")
            sldIdLst.append("<p:sldId id=\"${255 + k}\" r:id=\"rId${k + 1}\"/>")
            presRels.append("<Relationship Id=\"rId${k + 1}\" Type=\"$ODREL/slide\" Target=\"slides/slide$k.xml\"/>")
        }

        val presentation = XMLH + "<p:presentation xmlns:a=\"$A\" xmlns:r=\"$ODREL\" xmlns:p=\"$P\">" +
            "<p:sldMasterIdLst><p:sldMasterId id=\"2147483648\" r:id=\"rId1\"/></p:sldMasterIdLst>" +
            "<p:sldIdLst>$sldIdLst</p:sldIdLst>" +
            "<p:sldSz cx=\"9144000\" cy=\"6858000\"/><p:notesSz cx=\"6858000\" cy=\"9144000\"/></p:presentation>"

        val master = XMLH + "<p:sldMaster xmlns:a=\"$A\" xmlns:r=\"$ODREL\" xmlns:p=\"$P\">" +
            "<p:cSld>$EMPTY_TREE</p:cSld>" +
            "<p:clrMap bg1=\"lt1\" tx1=\"dk1\" bg2=\"lt2\" tx2=\"dk2\" accent1=\"accent1\" accent2=\"accent2\" accent3=\"accent3\" accent4=\"accent4\" accent5=\"accent5\" accent6=\"accent6\" hlink=\"hlink\" folHlink=\"folHlink\"/>" +
            "<p:sldLayoutIdLst><p:sldLayoutId id=\"2147483649\" r:id=\"rId1\"/></p:sldLayoutIdLst></p:sldMaster>"

        val layout = XMLH + "<p:sldLayout xmlns:a=\"$A\" xmlns:p=\"$P\">" +
            "<p:cSld>$EMPTY_TREE</p:cSld><p:clrMapOvr><a:masterClrMapping/></p:clrMapOvr></p:sldLayout>"

        entries.add(0, "[Content_Types].xml" to (XMLH +
            "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">" +
            "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>" +
            "<Default Extension=\"xml\" ContentType=\"application/xml\"/>" +
            "<Override PartName=\"/ppt/presentation.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.presentationml.presentation.main+xml\"/>" +
            "<Override PartName=\"/ppt/slideMasters/slideMaster1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.presentationml.slideMaster+xml\"/>" +
            "<Override PartName=\"/ppt/slideLayouts/slideLayout1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.presentationml.slideLayout+xml\"/>" +
            "<Override PartName=\"/ppt/theme/theme1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.theme+xml\"/>" +
            typeOverrides + "</Types>"))
        entries.add(1, "_rels/.rels" to (XMLH +
            "<Relationships xmlns=\"$RELNS\"><Relationship Id=\"rId1\" Type=\"$ODREL/officeDocument\" Target=\"ppt/presentation.xml\"/></Relationships>"))
        entries.add("ppt/presentation.xml" to presentation)
        entries.add("ppt/_rels/presentation.xml.rels" to (XMLH + "<Relationships xmlns=\"$RELNS\">$presRels</Relationships>"))
        entries.add("ppt/slideMasters/slideMaster1.xml" to master)
        entries.add("ppt/slideMasters/_rels/slideMaster1.xml.rels" to (XMLH +
            "<Relationships xmlns=\"$RELNS\">" +
            "<Relationship Id=\"rId1\" Type=\"$ODREL/slideLayout\" Target=\"../slideLayouts/slideLayout1.xml\"/>" +
            "<Relationship Id=\"rId2\" Type=\"$ODREL/theme\" Target=\"../theme/theme1.xml\"/></Relationships>"))
        entries.add("ppt/slideLayouts/slideLayout1.xml" to layout)
        entries.add("ppt/slideLayouts/_rels/slideLayout1.xml.rels" to (XMLH +
            "<Relationships xmlns=\"$RELNS\"><Relationship Id=\"rId1\" Type=\"$ODREL/slideMaster\" Target=\"../slideMasters/slideMaster1.xml\"/></Relationships>"))
        entries.add("ppt/theme/theme1.xml" to THEME)

        zip(out, entries)
    }
}
