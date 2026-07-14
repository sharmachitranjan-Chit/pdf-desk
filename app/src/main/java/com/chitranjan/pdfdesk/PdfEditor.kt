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
     */
    data class TextRun(
        val text: String,
        val x: Float,          // left edge
        val baselineY: Float,  // text baseline
        val width: Float,      // advance width of the whole run
        val fontSizePt: Float, // dominant font size
        val serif: Boolean,    // dominant font style flag
        val ascent: Float = 0f // measured glyph height above baseline (0 = unknown)
    ) {
        // Cover box: measured ascent when we have it, otherwise size-based guess.
        val boxX: Float get() = x
        val boxY: Float get() = baselineY - fontSizePt * 0.30f
        val boxW: Float get() = width
        val boxH: Float get() = (if (ascent > 0f) ascent else fontSizePt * 0.92f) + fontSizePt * 0.34f
    }

    /**
     * Detect text runs on one page. Returns empty if the page has no text
     * layer (i.e. it's a scan). Only meaningful for rotation == 0 pages —
     * callers must gate on that. v1 edits HORIZONTAL text only; rotated
     * labels (sideways table headers etc.) are filtered out, otherwise
     * their bounding boxes cover half the page and steal every tap.
     */
    fun detectTextRuns(work: File, pageIndex: Int): List<TextRun> {
        val doc = PDDocument.load(work)
        try {
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
            if (collected.isEmpty()) return emptyList()

            // Convert to PDF user space and group into baselines.
            data class Glyph(val ch: String, val x: Float, val y: Float, val w: Float,
                             val h: Float, val size: Float, val serif: Boolean)
            val glyphs = collected.mapNotNull { tp ->
                val ch = tp.unicode ?: return@mapNotNull null
                if (ch.isEmpty()) return@mapNotNull null
                // Horizontal text only in v1.
                val dir = tp.dir
                if (dir > 0.5f && dir < 359.5f) return@mapNotNull null
                val size = if (tp.fontSizeInPt > 0f) tp.fontSizeInPt else tp.fontSize
                if (size <= 0f) return@mapNotNull null
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
                    h = tp.heightDir,                        // measured height above baseline
                    size = size,
                    serif = serif
                )
            }
            if (glyphs.isEmpty()) return emptyList()

            // Group by baseline (tolerance scales with font size), then split
            // each line into runs wherever a horizontal gap is clearly wider
            // than word spacing (i.e. a table-column boundary).
            val lines = ArrayList<MutableList<Glyph>>()
            glyphs.sortedWith(compareByDescending<Glyph> { it.y }.thenBy { it.x }).forEach { gl ->
                val line = lines.lastOrNull()
                val tol = maxOf(2f, gl.size * 0.3f)
                if (line != null && kotlin.math.abs(line.last().y - gl.y) <= tol) line.add(gl)
                else lines.add(mutableListOf(gl))
            }

            val runs = ArrayList<TextRun>()
            lines.forEach { line ->
                line.sortBy { it.x }
                var start = 0
                for (i in 1..line.size) {
                    val breakHere = i == line.size || run {
                        val prev = line[i - 1]
                        val gap = line[i].x - (prev.x + prev.w)
                        gap > maxOf(prev.size, line[i].size) * 1.45f
                    }
                    if (breakHere) {
                        val seg = line.subList(start, i)
                        val text = seg.joinToString("") { it.ch }
                        if (text.isNotBlank()) {
                            val x0 = seg.first().x
                            val x1 = seg.last().x + seg.last().w
                            val size = seg.groupBy { it.size }.maxByOrNull { it.value.size }!!.key
                            val measured = seg.maxOf { it.h }
                            val serifCount = seg.count { it.serif }
                            runs.add(
                                TextRun(
                                    text = text.trim(),
                                    x = x0,
                                    baselineY = seg.map { it.y }.average().toFloat(),
                                    width = x1 - x0,
                                    fontSizePt = size,
                                    serif = serifCount * 2 >= seg.size,
                                    ascent = if (measured > 0f) measured * 1.06f else 0f
                                )
                            )
                        }
                        start = i
                    }
                }
            }
            return runs
        } finally { doc.close() }
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
