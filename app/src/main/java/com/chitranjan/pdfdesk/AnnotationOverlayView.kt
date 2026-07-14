package com.chitranjan.pdfdesk

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs

/**
 * Transparent layer placed exactly over the rendered page bitmap. It captures
 * touch gestures for three tools and holds the resulting marks in VIEW pixels
 * (which equal bitmap pixels, because the view is sized to the bitmap). The
 * activity later converts these to PDF points via PdfEditor.toPdf.
 */
class AnnotationOverlayView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    enum class Tool { HIGHLIGHT, DRAW, NOTE }

    var tool: Tool = Tool.HIGHLIGHT
    var drawColor: Int = Color.parseColor("#E23B3B")
    var strokeWidthPx: Float = 7f

    data class VStroke(val pts: MutableList<PointF>, val color: Int, val width: Float)
    data class VNote(val p: PointF, val text: String)

    private val highlights = ArrayList<RectF>()
    private val strokes = ArrayList<VStroke>()
    private val notes = ArrayList<VNote>()

    private enum class Kind { HL, STROKE, NOTE }
    private val history = ArrayList<Kind>()

    private var curStroke: VStroke? = null
    private var hlStart: PointF? = null
    private var hlCur: PointF? = null

    /** Activity supplies note text; it calls addNote() after the dialog. */
    var onNoteRequested: ((PointF) -> Unit)? = null

    private val hlPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#59F2E633") // translucent yellow preview
    }
    private val inkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val notePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#D8A43C")
    }
    private val noteBorder = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        color = Color.parseColor("#37452D")
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val x = event.x
        val y = event.y
        when (tool) {
            Tool.HIGHLIGHT -> handleHighlight(event, x, y)
            Tool.DRAW -> handleDraw(event, x, y)
            Tool.NOTE -> if (event.action == MotionEvent.ACTION_UP) {
                onNoteRequested?.invoke(PointF(x, y))
            }
        }
        return true
    }

    private fun handleHighlight(event: MotionEvent, x: Float, y: Float) {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> { hlStart = PointF(x, y); hlCur = PointF(x, y) }
            MotionEvent.ACTION_MOVE -> { hlCur = PointF(x, y); invalidate() }
            MotionEvent.ACTION_UP -> {
                val s = hlStart; val c = hlCur
                if (s != null && c != null && abs(c.x - s.x) > 6 && abs(c.y - s.y) > 6) {
                    highlights.add(RectF(minOf(s.x, c.x), minOf(s.y, c.y), maxOf(s.x, c.x), maxOf(s.y, c.y)))
                    history.add(Kind.HL)
                }
                hlStart = null; hlCur = null; invalidate()
            }
        }
    }

    private fun handleDraw(event: MotionEvent, x: Float, y: Float) {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                curStroke = VStroke(mutableListOf(PointF(x, y)), drawColor, strokeWidthPx)
            }
            MotionEvent.ACTION_MOVE -> { curStroke?.pts?.add(PointF(x, y)); invalidate() }
            MotionEvent.ACTION_UP -> {
                curStroke?.let {
                    if (it.pts.size >= 2) { strokes.add(it); history.add(Kind.STROKE) }
                }
                curStroke = null; invalidate()
            }
        }
    }

    fun addNote(p: PointF, text: String) {
        if (text.isNotBlank()) { notes.add(VNote(p, text)); history.add(Kind.NOTE); invalidate() }
    }

    fun undo() {
        if (history.isEmpty()) return
        when (history.removeAt(history.size - 1)) {
            Kind.HL -> if (highlights.isNotEmpty()) highlights.removeAt(highlights.size - 1)
            Kind.STROKE -> if (strokes.isNotEmpty()) strokes.removeAt(strokes.size - 1)
            Kind.NOTE -> if (notes.isNotEmpty()) notes.removeAt(notes.size - 1)
        }
        invalidate()
    }

    fun hasContent(): Boolean = highlights.isNotEmpty() || strokes.isNotEmpty() || notes.isNotEmpty()

    fun getHighlights(): List<RectF> = highlights
    fun getStrokes(): List<VStroke> = strokes
    fun getNotes(): List<VNote> = notes

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        highlights.forEach { canvas.drawRect(it, hlPaint) }
        hlStart?.let { s -> hlCur?.let { c -> canvas.drawRect(
            minOf(s.x, c.x), minOf(s.y, c.y), maxOf(s.x, c.x), maxOf(s.y, c.y), hlPaint) } }

        strokes.forEach { drawStroke(canvas, it) }
        curStroke?.let { drawStroke(canvas, it) }

        notes.forEach { n ->
            canvas.drawRect(n.p.x - 10f, n.p.y - 20f, n.p.x + 10f, n.p.y, notePaint)
            canvas.drawRect(n.p.x - 10f, n.p.y - 20f, n.p.x + 10f, n.p.y, noteBorder)
        }
    }

    private fun drawStroke(canvas: Canvas, s: VStroke) {
        if (s.pts.size < 2) return
        inkPaint.color = s.color
        inkPaint.strokeWidth = s.width
        val path = Path()
        path.moveTo(s.pts[0].x, s.pts[0].y)
        for (i in 1 until s.pts.size) path.lineTo(s.pts[i].x, s.pts[i].y)
        canvas.drawPath(path, inkPaint)
    }
}
