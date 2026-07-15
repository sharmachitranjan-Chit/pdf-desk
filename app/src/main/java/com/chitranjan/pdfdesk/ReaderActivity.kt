package com.chitranjan.pdfdesk

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import com.github.barteksc.pdfviewer.PDFView
import com.github.barteksc.pdfviewer.scroll.DefaultScrollHandle
import com.github.barteksc.pdfviewer.util.FitPolicy
import com.shockwave.pdfium.PdfPasswordException
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import android.graphics.Color
import android.text.InputType
import android.view.MenuItem
import android.widget.EditText
import androidx.core.content.ContextCompat
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.bottomsheet.BottomSheetDialog
import java.io.File

class ReaderActivity : AppCompatActivity() {

    private lateinit var pdfView: PDFView
    private lateinit var tvPage: TextView
    private var currentPage = 0
    private var pageCount = 0
    private var displayMode = 0  // 0 Day, 1 Sepia, 2 Night
    private var needsReload = false
    private var title0 = "PDF Desk"

    private val exportDoc = registerForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        if (uri != null) {
            val ok = FileUtils.copyWorkingToUri(this, uri)
            toast(if (ok) "Saved." else "Save failed.")
        }
    }

    private val pickMerge = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) doMerge(uri)
    }

    private val exportDocx = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/vnd.openxmlformats-officedocument.wordprocessingml.document")
    ) { uri -> if (uri != null) runOfficeExport(uri, "docx") }
    private val exportXlsx = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
    ) { uri -> if (uri != null) runOfficeExport(uri, "xlsx") }
    private val exportPptx = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/vnd.openxmlformats-officedocument.presentationml.presentation")
    ) { uri -> if (uri != null) runOfficeExport(uri, "pptx") }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_reader)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar.setNavigationOnClickListener { finish() }
        pdfView = findViewById(R.id.pdfView)
        tvPage = findViewById(R.id.tvPage)
        fabTools = findViewById(R.id.fabTools)

        // deeper pinch-zoom range
        pdfView.minZoom = 1f
        pdfView.midZoom = 3f
        pdfView.maxZoom = 10f

        // Opened from another app via "Open with"?
        if (intent?.action == Intent.ACTION_VIEW && intent.data != null) {
            val uri: Uri = intent.data!!
            title0 = FileUtils.queryName(this, uri)
            if (!FileUtils.copyUriToWorking(this, uri)) {
                toast("Couldn't open this PDF."); finish(); return
            }
            RecentStore.add(this, uri.toString(), title0, FileUtils.querySize(this, uri))
        } else {
            title0 = intent.getStringExtra("title") ?: "PDF Desk"
        }
        toolbar.title = title0
        toolbar.menu.add("Search").setIcon(R.drawable.ic_search)
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        toolbar.setOnMenuItemClickListener { searchDialog(); true }
        tintOverlay = findViewById(R.id.tintOverlay)
        fabTools.setOnClickListener { showToolsSheet() }
        applyDisplayMode()

        load(0)
    }

    override fun onResume() {
        super.onResume()
        if (needsReload) { needsReload = false; load(currentPage) }
    }

    private fun load(page: Int) {
        val f = FileUtils.workingFile(this)
        if (!f.exists()) { toast("No document loaded."); finish(); return }
        pdfView.fromFile(f)
            .defaultPage(page.coerceAtLeast(0))
            .enableSwipe(true)
            .swipeHorizontal(false)
            .enableDoubletap(true)
            .enableAnnotationRendering(true)
            .spacing(6)
            .pageFitPolicy(FitPolicy.WIDTH)
            .enableAntialiasing(true)
            .scrollHandle(DefaultScrollHandle(this))
            .onTap { toggleChrome(); false }
            .onPageScroll { _, _ -> setChromeVisible(false) }
            .nightMode(displayMode == 2)
            .onPageChange { p, count ->
                currentPage = p
                pageCount = count
                tvPage.text = "${p + 1} / $count"
            }
            .onError { t ->
                if (t is PdfPasswordException) promptPassword(false)
                else toast("Could not render this PDF.")
            }
            .load()
        setChromeVisible(true)
        scheduleAutoHide()
    }

    // ---- auto-hiding chrome (page chip + tools button) ----
    private lateinit var fabTools: android.view.View
    private var chromeVisible = true
    private val chromeHandler = Handler(Looper.getMainLooper())
    private val hideChrome = Runnable { setChromeVisible(false) }

    private fun scheduleAutoHide() {
        chromeHandler.removeCallbacks(hideChrome)
        chromeHandler.postDelayed(hideChrome, 3000)
    }

    private fun toggleChrome() {
        setChromeVisible(!chromeVisible)
        if (chromeVisible) scheduleAutoHide()
    }

    private fun setChromeVisible(v: Boolean) {
        if (v == chromeVisible && tvPage.alpha == (if (v) 1f else 0f)) return
        chromeVisible = v
        val a = if (v) 1f else 0f
        if (v) { tvPage.visibility = android.view.View.VISIBLE; fabTools.visibility = android.view.View.VISIBLE }
        tvPage.animate().alpha(a).setDuration(160).start()
        fabTools.animate().alpha(a).setDuration(160).withEndAction {
            if (!chromeVisible) { tvPage.visibility = android.view.View.INVISIBLE; fabTools.visibility = android.view.View.INVISIBLE }
        }.start()
    }

    private fun showToolsSheet() {
        val sheet = BottomSheetDialog(this)
        sheet.setContentView(R.layout.sheet_tools)
        fun row(id: Int, action: () -> Unit) {
            sheet.findViewById<android.view.View>(id)?.setOnClickListener {
                sheet.dismiss(); action()
            }
        }
        row(R.id.rowEdit) {
            needsReload = true
            startActivity(Intent(this, TextEditActivity::class.java).putExtra("page", currentPage))
        }
        row(R.id.rowAnnotate) {
            needsReload = true
            startActivity(Intent(this, AnnotateActivity::class.java).putExtra("page", currentPage))
        }
        row(R.id.rowPages) { needsReload = true; startActivity(Intent(this, PageManagerActivity::class.java)) }
        row(R.id.rowReadMode) { startActivity(Intent(this, ReaderModeActivity::class.java)) }
        row(R.id.rowGoto) { goToPageDialog() }
        sheet.findViewById<android.widget.TextView>(R.id.lblNight)?.text =
            "Display: " + arrayOf("Day", "Sepia", "Night")[displayMode]
        row(R.id.rowNight) {
            displayMode = (displayMode + 1) % 3
            applyDisplayMode()
            load(currentPage)
            toast("Display: " + arrayOf("Day", "Sepia", "Night")[displayMode])
        }
        row(R.id.rowMerge) { pickMerge.launch(arrayOf("application/pdf")) }
        row(R.id.rowExport) { exportDoc.launch(sanitizedExportName()) }
        row(R.id.rowOffice) { officeExportDialog() }
        row(R.id.rowShare) { share() }
        sheet.show()
    }

    private fun goToPageDialog() {
        val input = EditText(this).apply {
            hint = "Page 1 – $pageCount"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
        }
        AlertDialog.Builder(this)
            .setTitle("Go to page")
            .setView(input)
            .setPositiveButton("Go") { _, _ ->
                val n = input.text.toString().toIntOrNull()
                if (n != null && n in 1..pageCount) pdfView.jumpTo(n - 1, true)
                else toast("Enter 1 – $pageCount")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun doMerge(uri: Uri) {
        toast("Merging…")
        val add = File(filesDir, "merge_add.pdf")
        Thread {
            val ok = try {
                contentResolver.openInputStream(uri).use { input ->
                    if (input == null) return@use false
                    add.outputStream().use { out -> input.copyTo(out) }
                    true
                }
            } catch (e: Exception) { false }
            var merged = false
            if (ok) {
                try {
                    PdfEditor.mergeAppend(FileUtils.workingFile(this), FileUtils.tempFile(this), add)
                    merged = true
                } catch (e: Exception) { merged = false }
            }
            add.delete()
            runOnUiThread {
                if (merged) { load(currentPage); toast("Merged.") } else toast("Merge failed.")
            }
        }.start()
    }

    private fun share() {
        try {
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", FileUtils.workingFile(this))
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "application/pdf"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(send, "Share PDF"))
        } catch (e: Exception) { toast("Share failed.") }
    }

    private lateinit var tintOverlay: android.view.View

    private fun applyDisplayMode() {
        when (displayMode) {
            2 -> {  // Night: inverted pages on a true-dark gutter
                pdfView.setBackgroundColor(Color.parseColor("#14161A"))
                tintOverlay.visibility = android.view.View.GONE
            }
            1 -> {  // Sepia: warm tint over normal pages, easier on the eyes
                pdfView.setBackgroundColor(ContextCompat.getColor(this, R.color.canvas))
                tintOverlay.setBackgroundColor(0x33C58F24)
                tintOverlay.visibility = android.view.View.VISIBLE
            }
            else -> {
                pdfView.setBackgroundColor(ContextCompat.getColor(this, R.color.canvas))
                tintOverlay.visibility = android.view.View.GONE
            }
        }
    }

    private fun promptPassword(retry: Boolean) {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            hint = "Password"
        }
        AlertDialog.Builder(this)
            .setTitle(if (retry) "Incorrect password — try again" else "This PDF is password-protected")
            .setView(input)
            .setCancelable(false)
            .setPositiveButton("Unlock") { _, _ ->
                val pw = input.text.toString()
                Thread {
                    try {
                        PdfEditor.decrypt(FileUtils.workingFile(this), FileUtils.tempFile(this), pw)
                        runOnUiThread {
                            toast("Unlocked.")
                            needsReload = false
                            load(currentPage)
                        }
                    } catch (e: InvalidPasswordException) {
                        runOnUiThread { promptPassword(true) }
                    } catch (e: Exception) {
                        runOnUiThread { toast("Could not unlock this PDF."); finish() }
                    }
                }.start()
            }
            .setNegativeButton("Cancel") { _, _ -> finish() }
            .show()
    }

    private fun searchDialog() {
        val input = EditText(this).apply { hint = "Search in document" }
        AlertDialog.Builder(this)
            .setTitle("Search")
            .setView(input)
            .setPositiveButton("Find") { _, _ ->
                val q = input.text.toString().trim()
                if (q.isEmpty()) return@setPositiveButton
                toast("Searching…")
                Thread {
                    val hits = try { PdfEditor.searchText(FileUtils.workingFile(this), q) }
                               catch (e: Exception) { emptyList() }
                    runOnUiThread {
                        if (hits.isEmpty()) { toast("No matches for \"$q\"."); return@runOnUiThread }
                        val labels = hits.map { (p, n, snip) ->
                            "Page ${p + 1} (${n}×) — …$snip…"
                        }.toTypedArray()
                        AlertDialog.Builder(this)
                            .setTitle("${hits.sumOf { it.second }} matches on ${hits.size} pages")
                            .setItems(labels) { _, i -> pdfView.jumpTo(hits[i].first, true) }
                            .setNegativeButton("Close", null)
                            .show()
                    }
                }.start()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun officeExportDialog() {
        val opts = arrayOf("Word document (.docx)", "Excel workbook (.xlsx)", "PowerPoint (.pptx)")
        AlertDialog.Builder(this)
            .setTitle("Export as")
            .setItems(opts) { _, which ->
                val base = title0.removeSuffix(".pdf").ifBlank { "document" }
                when (which) {
                    0 -> exportDocx.launch("$base.docx")
                    1 -> exportXlsx.launch("$base.xlsx")
                    2 -> exportPptx.launch("$base.pptx")
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun runOfficeExport(uri: Uri, kind: String) {
        toast("Exporting… this may take a moment.")
        Thread {
            var ok = false
            val tmp = File(filesDir, "export_tmp.$kind")
            try {
                val runPages = PdfEditor.extractAllLineRuns(FileUtils.workingFile(this))
                val strPages = runPages.map { pg -> pg.map { ln -> ln.map { it.text } } }
                when (kind) {
                    "docx" -> ExportFormats.buildDocx(runPages, tmp)
                    "xlsx" -> ExportFormats.buildXlsx(strPages, tmp)
                    "pptx" -> ExportFormats.buildPptx(strPages, tmp)
                }
                ok = FileUtils.copyFileToUri(this, tmp, uri)
            } catch (e: Exception) { ok = false }
            tmp.delete()
            runOnUiThread {
                toast(if (ok) "Exported. Note: this carries the extracted text/tables, not the PDF's exact layout."
                      else "Export failed.")
            }
        }.start()
    }

    private fun sanitizedExportName(): String {
        val base = title0.removeSuffix(".pdf").ifBlank { "document" }
        return "${base}_edited.pdf"
    }

    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_SHORT).show()
}
