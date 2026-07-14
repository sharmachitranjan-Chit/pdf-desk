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
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationText
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
}
