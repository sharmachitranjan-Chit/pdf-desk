package com.chitranjan.pdfdesk

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfRenderer
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewTreeObserver
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import java.util.concurrent.Executors
import kotlin.math.min

/**
 * Phase 2 v1: tap a text run → cover with bg-matched rect → redraw new text.
 * Unrotated, text-layer pages only. Detection + editing live in PdfEditor;
 * this screen renders, maps coordinates, samples background, and confirms.
 */
class TextEditActivity : AppCompatActivity() {

    private lateinit var container: FrameLayout
    private lateinit var img: ImageView
    private lateinit var overlay: TextRunOverlayView
    private val io = Executors.newSingleThreadExecutor()
    private val ui = Handler(Looper.getMainLooper())

    private var pageIndex = 0
    private var geom: PageGeom? = null
    private var bmp: Bitmap? = null
    private var runs: List<PdfEditor.TextRun> = emptyList()
    private var edited = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_text_edit)

        pageIndex = intent.getIntExtra("page", 0)
        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar.setNavigationOnClickListener { finish() }

        container = findViewById(R.id.editContainer)
        img = findViewById(R.id.imgPage)
        overlay = findViewById(R.id.runOverlay)
        overlay.onRunTapped = { i -> editDialog(i) }

        container.viewTreeObserver.addOnGlobalLayoutListener(object : ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                container.viewTreeObserver.removeOnGlobalLayoutListener(this)
                setupPage()
            }
        })
    }

    private fun setupPage() {
        val cw = container.width.coerceAtLeast(1)
        val ch = container.height.coerceAtLeast(1)
        io.execute {
            val work = FileUtils.workingFile(this)
            val g = try { PdfEditor.geometry(work, pageIndex) } catch (e: Exception) { null }
            if (g == null) { ui.post { toast("Couldn't load page."); finish() }; return@execute }
            if (g.rotation != 0) {
                ui.post { toast("Text editing works on unrotated pages only (for now)."); finish() }
                return@execute
            }
            val detected = try { PdfEditor.detectTextRuns(work, pageIndex) } catch (e: Exception) { emptyList() }
            val s = min(cw / g.visualW, ch / g.visualH)
            val w = (g.visualW * s).toInt().coerceAtLeast(1)
            val h = (g.visualH * s).toInt().coerceAtLeast(1)
            val rendered = renderPage(w, h)
            ui.post {
                if (rendered == null) { toast("Render failed."); finish(); return@post }
                geom = g
                bmp = rendered
                runs = detected
                img.layoutParams = img.layoutParams.apply { width = rendered.width; height = rendered.height }
                overlay.layoutParams = overlay.layoutParams.apply { width = rendered.width; height = rendered.height }
                img.setImageBitmap(rendered)
                overlay.setRuns(detected.map { screenBox(it) })
                img.requestLayout(); overlay.requestLayout()
                if (detected.isEmpty()) {
                    toast("No editable text found — this page may be a scan.")
                }
            }
        }
    }

    /** PDF user-space run box → bitmap/screen rect (unrotated pages only). */
    private fun screenBox(r: PdfEditor.TextRun): RectF {
        val g = geomOrThrow()
        val s = sPxPerPt()
        val left = (r.boxX - g.cropLLX) * s
        val right = (r.boxX + r.boxW - g.cropLLX) * s
        val top = (g.cropLLY + g.cropH - (r.boxY + r.boxH)) * s
        val bottom = (g.cropLLY + g.cropH - r.boxY) * s
        return RectF(left, top, right, bottom)
    }

    private fun geomOrThrow(): PageGeom = geom ?: throw IllegalStateException("geom")
    private fun sPxPerPt(): Float {
        val b = bmp ?: return 1f
        return b.width / geomOrThrow().visualW
    }

    private fun renderPage(w: Int, h: Int): Bitmap? = try {
        val pfd = ParcelFileDescriptor.open(FileUtils.workingFile(this), ParcelFileDescriptor.MODE_READ_ONLY)
        val r = PdfRenderer(pfd)
        val page = r.openPage(pageIndex)
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.eraseColor(Color.WHITE)
        page.render(out, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
        page.close(); r.close(); pfd.close()
        out
    } catch (e: Exception) { null }

    /**
     * Median colour of pixels sampled in a thin ring just OUTSIDE the run box —
     * that's the paper/fill the covering rect must match. Median resists
     * stray dark pixels from neighbouring glyphs.
     */
    private fun sampleBackground(box: RectF): FloatArray {
        val b = bmp ?: return floatArrayOf(1f, 1f, 1f)
        val pad = 4f
        val rs = ArrayList<Int>(); val gs = ArrayList<Int>(); val bs = ArrayList<Int>()
        fun grab(x: Float, y: Float) {
            val xi = x.toInt().coerceIn(0, b.width - 1)
            val yi = y.toInt().coerceIn(0, b.height - 1)
            val c = b.getPixel(xi, yi)
            rs.add(Color.red(c)); gs.add(Color.green(c)); bs.add(Color.blue(c))
        }
        val steps = 8
        for (i in 0..steps) {
            val x = box.left + (box.width() * i / steps)
            grab(x, box.top - pad)      // just above
            grab(x, box.bottom + pad)   // just below
        }
        for (i in 0..2) {
            val y = box.top + (box.height() * i / 2)
            grab(box.left - pad, y)     // just left
            grab(box.right + pad, y)    // just right
        }
        fun med(l: MutableList<Int>): Float { l.sort(); return l[l.size / 2] / 255f }
        return floatArrayOf(med(rs), med(gs), med(bs))
    }

    private fun editDialog(runIdx: Int) {
        val run = runs.getOrNull(runIdx) ?: return
        val input = EditText(this).apply {
            setText(run.text)
            setSelection(run.text.length)
        }
        AlertDialog.Builder(this)
            .setTitle("Edit text")
            .setView(input)
            .setPositiveButton("Apply") { _, _ -> applyEdit(run, input.text.toString()) }
            .setNeutralButton("Erase") { _, _ -> applyEdit(run, "") }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun applyEdit(run: PdfEditor.TextRun, newText: String) {
        val bg = sampleBackground(screenBox(run))
        toast("Applying…")
        io.execute {
            var ok = false
            try {
                val fontAsset = if (run.serif) "DejaVuSerif.ttf" else "DejaVuSans.ttf"
                assets.open(fontAsset).use { font ->
                    PdfEditor.editTextRun(
                        FileUtils.workingFile(this), FileUtils.tempFile(this), pageIndex,
                        run, newText, font, bg[0], bg[1], bg[2]
                    )
                }
                ok = true
            } catch (e: Exception) { ok = false }
            ui.post {
                if (ok) {
                    edited = true
                    setResult(RESULT_OK)
                    toast(if (newText.isEmpty()) "Text erased." else "Text replaced.")
                    setupPage()   // re-render + re-detect so further edits see the new state
                } else toast("Edit failed.")
            }
        }
    }

    override fun onBackPressed() {
        if (edited) setResult(RESULT_OK)
        super.onBackPressed()
    }

    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_SHORT).show()
}

/**
 * Shows detected runs as faint amber boxes; reports taps by run index.
 * Boxes are in bitmap coordinates (the view is sized exactly to the bitmap).
 */
class TextRunOverlayView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    var onRunTapped: ((Int) -> Unit)? = null
    private var boxes: List<RectF> = emptyList()

    private val fill = Paint().apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#26E0A62E")   // 15% amber
    }
    private val stroke = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        color = Color.parseColor("#99E0A62E")
        isAntiAlias = true
    }

    fun setRuns(b: List<RectF>) { boxes = b; invalidate() }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        boxes.forEach { r ->
            canvas.drawRoundRect(r, 4f, 4f, fill)
            canvas.drawRoundRect(r, 4f, 4f, stroke)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_UP) {
            // Small slop so thin runs are still tappable.
            val slop = 8f
            val hit = boxes.indexOfFirst {
                RectF(it.left - slop, it.top - slop, it.right + slop, it.bottom + slop)
                    .contains(event.x, event.y)
            }
            if (hit >= 0) { onRunTapped?.invoke(hit); performClick() }
        }
        return true
    }

    override fun performClick(): Boolean { super.performClick(); return true }
}
