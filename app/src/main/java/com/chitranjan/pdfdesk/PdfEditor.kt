package com.chitranjan.pdfdesk

import com.tom_roush.pdfbox.multipdf.PDFMergerUtility
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.graphics.blend.BlendMode
import com.tom_roush.pdfbox.pdmodel.graphics.color.PDColor
import com.tom_roush.pdfbox.pdmodel.graphics.color.PDDeviceRGB
import com.tom_roush.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState
import com.tom_roush.pdfbox.pdmodel.font.PDType0Font
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationText
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import java.io.File

/** Geometry of a single page, in PDF points (1/72 inch). */
data class PageGeom(
    val rotation: Int,
    val cropLLX: Float,
    val cropLLY: Float,
    val cropW: Float,
    val cropH: Float
) {
    /** Visible width/height AFTER the page's /Rotate is applied. */
    val visualW: Float get() = if (rotation == 90 || rotation == 270) cropH else cropW
    val visualH: Float get() = if (rotation == 90 || rotation == 270) cropW else cropH
}

data class InkStroke(val points: List<FloatArray>, val r: Float, val g: Float, val b: Float, val widthPts: Float)
data class NoteItem(val x: Float, val y: Float, val text: String)

/**
 * All PDF reading/editing goes through PDFBox-Android here. Mutating ops write
 * to a temp file first, then atomically replace the working file, so we never
 * corrupt the open document.
 */
object PdfEditor {

    fun pageCount(work: File): Int {
        PDDocument.load(work).use { return it.numberOfPages }
    }

    fun geometry(work: File, index: Int): PageGeom {
        PDDocument.load(work).use { doc -> return geomOf(doc.getPage(index)) }
    }

    fun allGeometry(work: File): List<PageGeom> {
        PDDocument.load(work).use { doc ->
            return (0 until doc.numberOfPages).map { geomOf(doc.getPage(it)) }
        }
    }

    private fun geomOf(page: PDPage): PageGeom {
        val box = page.cropBox
        var rot = page.rotation % 360
        if (rot < 0) rot += 360
        return PageGeom(rot, box.lowerLeftX, box.lowerLeftY, box.width, box.height)
    }

    /**
     * Map a point on the rendered bitmap (top-left origin, y down, pixels) to
     * absolute PDF user-space coordinates (bottom-left origin, y up, points),
     * inverting the page's /Rotate so drawn content lands where the user sees it.
     * sPxPerPt = bitmapWidthPx / geom.visualW.
     */
    fun toPdf(g: PageGeom, vxPx: Float, vyPx: Float, sPxPerPt: Float): FloatArray {
        val vx = vxPx / sPxPerPt   // visual points, x to the right
        val vy = vyPx / sPxPerPt   // visual points, y downward from top
        val wp = g.cropW
        val hp = g.cropH
        val x: Float
        val y: Float
        when (g.rotation) {
            90 -> { x = vy; y = vx }
            180 -> { x = wp - vx; y = vy }
            270 -> { x = wp - vy; y = hp - vx }
            else -> { x = vx; y = hp - vy }   // 0
        }
        return floatArrayOf(x + g.cropLLX, y + g.cropLLY)
    }

    // ---------- Page operations ----------

    fun rotatePages(work: File, tmp: File, indices: List<Int>, delta: Int) {
        val doc = PDDocument.load(work)
        try {
            indices.forEach { i ->
                if (i in 0 until doc.numberOfPages) {
                    val p = doc.getPage(i)
                    var r = (p.rotation + delta) % 360
                    if (r < 0) r += 360
                    p.rotation = r
                }
            }
            doc.save(tmp)
        } finally { doc.close() }
        replace(work, tmp)
    }

    fun deletePages(work: File, tmp: File, indices: List<Int>) {
        val doc = PDDocument.load(work)
        try {
            indices.sortedDescending().forEach { i ->
                if (i in 0 until doc.numberOfPages && doc.numberOfPages > 1) doc.removePage(i)
            }
            doc.save(tmp)
        } finally { doc.close() }
        replace(work, tmp)
    }

    fun reorderPages(work: File, tmp: File, order: List<Int>) {
        val doc = PDDocument.load(work)
        try {
            val original = ArrayList<PDPage>()
            for (i in 0 until doc.numberOfPages) original.add(doc.getPage(i))
            val ordered = order.mapNotNull { original.getOrNull(it) }
            for (i in doc.numberOfPages - 1 downTo 0) doc.removePage(i)
            ordered.forEach { doc.addPage(it) }
            doc.save(tmp)
        } finally { doc.close() }
        replace(work, tmp)
    }

    fun extractPages(work: File, out: File, indices: List<Int>) {
        val src = PDDocument.load(work)
        val dst = PDDocument()
        try {
            indices.sorted().forEach { i ->
                if (i in 0 until src.numberOfPages) dst.importPage(src.getPage(i))
            }
            dst.save(out)
        } finally { dst.close(); src.close() }
    }

    /** out1 = pages 0..index inclusive, out2 = the rest. */
    fun splitAt(work: File, out1: File, out2: File, index: Int) {
        val src = PDDocument.load(work)
        val d1 = PDDocument()
        val d2 = PDDocument()
        try {
            for (i in 0 until src.numberOfPages) {
                (if (i <= index) d1 else d2).importPage(src.getPage(i))
            }
            d1.save(out1)
            d2.save(out2)
        } finally { d1.close(); d2.close(); src.close() }
    }

    fun mergeAppend(work: File, tmp: File, add: File) {
        val base = PDDocument.load(work)
        val other = PDDocument.load(add)
        try {
            PDFMergerUtility().appendDocument(base, other)
            base.save(tmp)
        } finally { other.close(); base.close() }
        replace(work, tmp)
    }

    // ---------- Annotations (applied to one page in a single pass) ----------

    fun applyAnnotations(
        work: File, tmp: File, pageIndex: Int,
        highlights: List<FloatArray>,   // each: [x, y, w, h] in PDF points
        strokes: List<InkStroke>,
        notes: List<NoteItem>
    ) {
        val doc = PDDocument.load(work)
        try {
            val page = doc.getPage(pageIndex)
            val cs = PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true, true)

            // Highlights: translucent yellow, multiply so text stays readable.
            if (highlights.isNotEmpty()) {
                val gs = PDExtendedGraphicsState()
                gs.nonStrokingAlphaConstant = 0.35f
                gs.blendMode = BlendMode.MULTIPLY
                cs.setGraphicsStateParameters(gs)
                cs.setNonStrokingColor(1f, 0.9f, 0.2f)
                highlights.forEach { r ->
                    cs.addRect(r[0], r[1], r[2], r[3])
                    cs.fill()
                }
            }

            // Opaque state for ink + note markers.
            val gsOpaque = PDExtendedGraphicsState()
            gsOpaque.nonStrokingAlphaConstant = 1f
            gsOpaque.strokingAlphaConstant = 1f
            gsOpaque.blendMode = BlendMode.NORMAL
            cs.setGraphicsStateParameters(gsOpaque)

            cs.setLineCapStyle(1)
            cs.setLineJoinStyle(1)
            strokes.forEach { s ->
                if (s.points.size >= 2) {
                    cs.setStrokingColor(s.r, s.g, s.b)
                    cs.setLineWidth(s.widthPts)
                    cs.moveTo(s.points[0][0], s.points[0][1])
                    for (i in 1 until s.points.size) cs.lineTo(s.points[i][0], s.points[i][1])
                    cs.stroke()
                }
            }

            // Visible amber marker for each note (guarantees visibility everywhere).
            if (notes.isNotEmpty()) {
                cs.setNonStrokingColor(0.85f, 0.64f, 0.24f)
                notes.forEach { n -> cs.addRect(n.x - 7f, n.y - 14f, 14f, 14f); cs.fill() }
            }
            cs.close()

            // Real, tappable text annotations carry the note text.
            notes.forEach { n ->
                val annot = PDAnnotationText()
                annot.contents = n.text
                annot.name = PDAnnotationText.NAME_NOTE
                annot.rectangle = PDRectangle(n.x - 7f, n.y - 14f, 14f, 14f)
                annot.color = PDColor(floatArrayOf(0.85f, 0.64f, 0.24f), PDDeviceRGB.INSTANCE)
                annot.constantOpacity = 1f
                annot.setOpen(false)
                page.annotations.add(annot)
            }

            doc.save(tmp)
        } finally { doc.close() }
        replace(work, tmp)
    }

    private fun replace(work: File, tmp: File) {
        if (work.exists()) work.delete()
        if (!tmp.renameTo(work)) {
            tmp.copyTo(work, overwrite = true)
            tmp.delete()
        }
    }

    // ---------- Phase 2: in-place text editing (unrotated pages only) ----------

    /**
     * One editable run of text on a line. All geometry is in absolute PDF
     * user-space points (bottom-left origin, y up), same space toPdf outputs.
     * The cover box is carried explicitly, derived from MEASURED glyph
     * heights — never from the font's declared size, which many PDFs
     * (matrix-scaled text) report wildly wrong.
     */
    data class TextRun(
        val text: String,
        val x: Float,          // left edge
        val baselineY: Float,  // text baseline
        val width: Float,      // advance width of the whole run
        val fontSizePt: Float, // sanity-checked size for redraw
        val serif: Boolean,    // dominant font style flag
        val boxX: Float,       // cover rect (y-up)
        val boxY: Float,
        val boxW: Float,
        val boxH: Float
    )

    /**
     * Detect text runs on one page. Returns empty if the page has no text
     * layer (i.e. it's a scan). Only meaningful for rotation == 0 pages —
     * callers must gate on that. v1 edits HORIZONTAL text only.
     *
     * All grouping thresholds are driven by tp.heightDir (the measured
     * glyph height), NOT fontSizeInPt: on matrix-scaled PDFs fontSizeInPt
     * can be 10x reality, which merges whole columns into one tall run
     * and floats every box off its line.
     */
    fun detectTextRuns(work: File, pageIndex: Int): List<TextRun> {
        PDDocument.load(work).use { doc -> return linesOfRuns(doc, pageIndex).flatten() }
    }

    /** Per-page lines of runs (columns split at wide gaps) — for Office export. */
    fun extractAllLineRuns(work: File): List<List<List<TextRun>>> {
        PDDocument.load(work).use { doc ->
            return (0 until doc.numberOfPages).map { p ->
                try { linesOfRuns(doc, p) } catch (e: Exception) { emptyList() }
            }
        }
    }

    /**
     * Remove encryption from the working copy using the supplied password,
     * so viewing AND editing work on protected PDFs from then on.
     * Throws InvalidPasswordException on a wrong password.
     */
    fun decrypt(work: File, tmp: File, password: String) {
        PDDocument.load(work, password).use { doc ->
            doc.setAllSecurityToBeRemoved(true)
            doc.save(tmp)
        }
        replace(work, tmp)
    }

    /** Whole-document plain text, reading order — for reflow reading mode. */
    fun extractText(work: File): String {
        PDDocument.load(work).use { doc ->
            val s = PDFTextStripper()
            s.sortByPosition = true
            return s.getText(doc)
        }
    }

    /** Case-insensitive keyword search. Returns (pageIndex, hitCount, snippet). */
    fun searchText(work: File, query: String): List<Triple<Int, Int, String>> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return emptyList()
        val out = ArrayList<Triple<Int, Int, String>>()
        PDDocument.load(work).use { doc ->
            val stripper = PDFTextStripper()
            stripper.sortByPosition = true
            for (p in 0 until doc.numberOfPages) {
                stripper.startPage = p + 1
                stripper.endPage = p + 1
                val text = try { stripper.getText(doc) } catch (e: Exception) { "" }
                val lower = text.lowercase()
                var idx = lower.indexOf(q)
                if (idx < 0) continue
                var count = 0
                val first = idx
                while (idx >= 0) { count++; idx = lower.indexOf(q, idx + q.length) }
                val s = (first - 28).coerceAtLeast(0)
                val e = (first + q.length + 28).coerceAtMost(text.length)
                val snippet = text.substring(s, e).replace(Regex("\\s+"), " ").trim()
                out.add(Triple(p, count, snippet))
            }
        }
        return out
    }

    private fun linesOfRuns(doc: PDDocument, pageIndex: Int): List<List<TextRun>> {
            val g = geomOf(doc.getPage(pageIndex))
            val collected = ArrayList<TextPosition>()
            val stripper = object : PDFTextStripper() {
                override fun processTextPosition(tp: TextPosition) {
                    collected.add(tp)
                    super.processTextPosition(tp)
                }
            }
            stripper.startPage = pageIndex + 1
            stripper.endPage = pageIndex + 1
            stripper.getText(doc)  // drives processTextPosition
            if (collected.isEmpty()) return emptyList()   // no text layer

            data class Glyph(val ch: String, val x: Float, val y: Float, val w: Float,
                             val h: Float, val size: Float, val serif: Boolean)
            val glyphs = collected.mapNotNull { tp ->
                val ch = tp.unicode ?: return@mapNotNull null
                if (ch.isEmpty()) return@mapNotNull null
                val dir = tp.dir
                if (dir > 0.5f && dir < 359.5f) return@mapNotNull null  // horizontal only
                val serif = runCatching {
                    val d = tp.font?.fontDescriptor
                    (d?.isSerif == true) || (tp.font?.name ?: "").let {
                        it.contains("Times", true) || it.contains("Serif", true) ||
                        it.contains("Georgia", true) || it.contains("Roman", true)
                    }
                }.getOrDefault(false)
                Glyph(
                    ch = ch,
                    x = g.cropLLX + tp.xDirAdj,
                    y = g.cropLLY + g.cropH - tp.yDirAdj,   // baseline, y-up
                    w = tp.widthDirAdj,
                    h = tp.heightDir,                        // measured, trustworthy
                    size = tp.fontSizeInPt,                  // NOT trustworthy — sanity-checked later
                    serif = serif
                )
            }
            if (glyphs.isEmpty()) return emptyList()

            // Typical glyph height on this page (median of measured heights).
            val positiveH = glyphs.mapNotNull { if (it.h > 0.5f) it.h else null }.sorted()
            val pageH = if (positiveH.isEmpty()) 7f else positiveH[positiveH.size / 2]

            // ---- group into lines: tolerance from measured height ----
            val lines = ArrayList<MutableList<Glyph>>()
            glyphs.sortedWith(compareByDescending<Glyph> { it.y }.thenBy { it.x }).forEach { gl ->
                val line = lines.lastOrNull()
                val hRef = if (gl.h > 0.5f) gl.h else pageH
                val tol = maxOf(2.5f, 0.42f * hRef)
                if (line != null && kotlin.math.abs(line.first().y - gl.y) <= tol) line.add(gl)
                else lines.add(mutableListOf(gl))
            }

            // ---- split each line into runs at clear column gaps ----
            val out = ArrayList<List<TextRun>>()
            lines.forEach { line ->
                val runs = ArrayList<TextRun>()
                line.sortBy { it.x }
                var start = 0
                for (i in 1..line.size) {
                    val breakHere = i == line.size || run {
                        val prev = line[i - 1]
                        val cur = line[i]
                        val gap = cur.x - (prev.x + prev.w)
                        val hLoc = maxOf(prev.h, cur.h, pageH * 0.8f)
                        gap > maxOf(4f, 1.6f * hLoc)
                    }
                    if (breakHere) {
                        var seg = line.subList(start, i).toList()
                        // trim blank glyphs off both ends so geometry hugs visible text
                        seg = seg.dropWhile { it.ch.isBlank() }.dropLastWhile { it.ch.isBlank() }
                        if (seg.isNotEmpty()) {
                            val text = seg.joinToString("") { it.ch }
                            if (text.isNotBlank()) {
                                val x0 = seg.first().x
                                val x1 = seg.last().x + seg.last().w
                                val segH = seg.mapNotNull { if (it.h > 0.5f) it.h else null }
                                val hMax = if (segH.isEmpty()) pageH else segH.max()
                                val ys = seg.map { it.y }.sorted()
                                val baseline = ys[ys.size / 2]
                                // redraw size: trust fontSizeInPt only when plausible vs measured height
                                val szs = seg.mapNotNull { if (it.size > 0.5f) it.size else null }.sorted()
                                val szMed = if (szs.isEmpty()) 0f else szs[szs.size / 2]
                                val size = if (szMed >= 0.9f * hMax && szMed <= 2.4f * hMax) szMed
                                           else hMax / 0.72f
                                val serifCount = seg.count { it.serif }
                                runs.add(
                                    TextRun(
                                        text = text.trim(),
                                        x = x0,
                                        baselineY = baseline,
                                        width = x1 - x0,
                                        fontSizePt = size,
                                        serif = serifCount * 2 >= seg.size,
                                        boxX = x0,
                                        boxY = baseline - hMax * 0.32f,
                                        boxW = x1 - x0,
                                        boxH = hMax * 1.42f
                                    )
                                )
                            }
                        }
                        start = i
                    }
                }
                if (runs.isNotEmpty()) out.add(runs)
            }
            return out
    }

    /**
     * Replace one run: cover the original with a bg-matched rect, then draw
     * newText at the same left edge + baseline with the bundled font.
     * Shrinks the font size to fit the original box when newText is longer.
     * bg* are 0..1 sRGB sampled from the rendered page around the run.
     */
    fun editTextRun(
        work: File, tmp: File, pageIndex: Int,
        run: TextRun, newText: String,
        fontBytes: java.io.InputStream,
        bgR: Float, bgG: Float, bgB: Float
    ) {
        val doc = PDDocument.load(work)
        try {
            val page = doc.getPage(pageIndex)
            val font = PDType0Font.load(doc, fontBytes)

            var size = run.fontSizePt
            if (newText.isNotEmpty()) {
                val natural = font.getStringWidth(newText) / 1000f * size
                if (natural > run.width && natural > 0f) {
                    size = maxOf(5f, size * (run.width / natural))
                }
            }

            val cs = PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true, true)
            val gs = PDExtendedGraphicsState()
            gs.nonStrokingAlphaConstant = 1f
            gs.blendMode = BlendMode.NORMAL
            cs.setGraphicsStateParameters(gs)

            // Cover the original run (pad ~1pt each side against antialiased fringes).
            cs.setNonStrokingColor(bgR, bgG, bgB)
            cs.addRect(run.boxX - 1f, run.boxY - 1f, run.boxW + 2f, run.boxH + 2f)
            cs.fill()

            // Redraw.
            if (newText.isNotEmpty()) {
                cs.setNonStrokingColor(0f, 0f, 0f)
                cs.beginText()
                cs.setFont(font, size)
                cs.newLineAtOffset(run.x, run.baselineY)
                cs.showText(newText)
                cs.endText()
            }
            cs.close()
            doc.save(tmp)
        } finally { doc.close() }
        replace(work, tmp)
    }
}
