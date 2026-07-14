package com.chitranjan.pdfdesk

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.view.MenuItem
import android.view.View
import android.view.ViewTreeObserver
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.min

class AnnotateActivity : AppCompatActivity() {

    private lateinit var container: FrameLayout
    private lateinit var img: ImageView
    private lateinit var overlay: AnnotationOverlayView
    private val io = Executors.newSingleThreadExecutor()
    private val ui = Handler(Looper.getMainLooper())

    private var pageIndex = 0
    private var geom: PageGeom? = null
    private var bmpW = 0f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_annotate)

        pageIndex = intent.getIntExtra("page", 0)
        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar.setNavigationOnClickListener { confirmDiscard() }
        toolbar.menu.add(0, 1, 0, "Save").setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        toolbar.setOnMenuItemClickListener { if (it.itemId == 1) { save(); true } else false }

        container = findViewById(R.id.annotContainer)
        img = findViewById(R.id.imgPage)
        overlay = findViewById(R.id.overlay)

        val btnHl = findViewById<MaterialButton>(R.id.btnHl)
        val btnDraw = findViewById<MaterialButton>(R.id.btnDraw)
        val btnNote = findViewById<MaterialButton>(R.id.btnNote)
        fun setTool(t: AnnotationOverlayView.Tool, active: MaterialButton) {
            overlay.tool = t
            listOf(btnHl, btnDraw, btnNote).forEach { it.alpha = 0.55f }
            active.alpha = 1f
        }
        setTool(AnnotationOverlayView.Tool.HIGHLIGHT, btnHl)
        btnHl.setOnClickListener { setTool(AnnotationOverlayView.Tool.HIGHLIGHT, btnHl) }
        btnDraw.setOnClickListener { setTool(AnnotationOverlayView.Tool.DRAW, btnDraw) }
        btnNote.setOnClickListener { setTool(AnnotationOverlayView.Tool.NOTE, btnNote) }

        findViewById<View>(R.id.col1).setOnClickListener { overlay.drawColor = Color.parseColor("#E23B3B") }
        findViewById<View>(R.id.col2).setOnClickListener { overlay.drawColor = Color.parseColor("#2D6CDF") }
        findViewById<View>(R.id.col3).setOnClickListener { overlay.drawColor = Color.parseColor("#2E2A22") }
        findViewById<MaterialButton>(R.id.btnUndo).setOnClickListener { overlay.undo() }

        overlay.onNoteRequested = { p -> noteDialog(p.x, p.y) }

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
            val g = try { PdfEditor.geometry(FileUtils.workingFile(this), pageIndex) } catch (e: Exception) { null }
            if (g == null) { ui.post { toast("Couldn't load page."); finish() }; return@execute }
            val s = min(cw / g.visualW, ch / g.visualH)
            val w = (g.visualW * s).toInt().coerceAtLeast(1)
            val h = (g.visualH * s).toInt().coerceAtLeast(1)
            val bmp = renderPage(w, h)
            ui.post {
                if (bmp == null) { toast("Render failed."); finish(); return@post }
                geom = g
                bmpW = bmp.width.toFloat()
                img.layoutParams = img.layoutParams.apply { width = bmp.width; height = bmp.height }
                overlay.layoutParams = overlay.layoutParams.apply { width = bmp.width; height = bmp.height }
                img.setImageBitmap(bmp)
                img.requestLayout(); overlay.requestLayout()
            }
        }
    }

    private fun renderPage(w: Int, h: Int): Bitmap? = try {
        val pfd = ParcelFileDescriptor.open(FileUtils.workingFile(this), ParcelFileDescriptor.MODE_READ_ONLY)
        val r = PdfRenderer(pfd)
        val page = r.openPage(pageIndex)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(Color.WHITE)
        page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
        page.close(); r.close(); pfd.close()
        bmp
    } catch (e: Exception) { null }

    private fun noteDialog(vx: Float, vy: Float) {
        val input = EditText(this).apply { hint = "Note text" }
        AlertDialog.Builder(this)
            .setTitle("Add note")
            .setView(input)
            .setPositiveButton("Add") { _, _ -> overlay.addNote(android.graphics.PointF(vx, vy), input.text.toString()) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun save() {
        val g = geom
        if (g == null || bmpW <= 0f) { finish(); return }
        if (!overlay.hasContent()) { toast("Nothing to save."); finish(); return }
        val s = bmpW / g.visualW

        val highlights = overlay.getHighlights().map { r ->
            val a = PdfEditor.toPdf(g, r.left, r.top, s)
            val b = PdfEditor.toPdf(g, r.right, r.bottom, s)
            floatArrayOf(min(a[0], b[0]), min(a[1], b[1]), abs(a[0] - b[0]), abs(a[1] - b[1]))
        }
        val strokes = overlay.getStrokes().map { st ->
            val pts = st.pts.map { PdfEditor.toPdf(g, it.x, it.y, s) }
            InkStroke(pts, Color.red(st.color) / 255f, Color.green(st.color) / 255f, Color.blue(st.color) / 255f, st.width / s)
        }
        val notes = overlay.getNotes().map { n ->
            val p = PdfEditor.toPdf(g, n.p.x, n.p.y, s)
            NoteItem(p[0], p[1], n.text)
        }

        toast("Saving…")
        io.execute {
            var ok = false
            try {
                PdfEditor.applyAnnotations(FileUtils.workingFile(this), FileUtils.tempFile(this), pageIndex, highlights, strokes, notes)
                ok = true
            } catch (e: Exception) { ok = false }
            ui.post { toast(if (ok) "Annotations saved." else "Save failed."); if (ok) finish() }
        }
    }

    private fun confirmDiscard() {
        if (!overlay.hasContent()) { finish(); return }
        AlertDialog.Builder(this)
            .setTitle("Discard annotations?")
            .setPositiveButton("Discard") { _, _ -> finish() }
            .setNegativeButton("Keep editing", null)
            .show()
    }

    override fun onBackPressed() { confirmDiscard() }

    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_SHORT).show()
}
