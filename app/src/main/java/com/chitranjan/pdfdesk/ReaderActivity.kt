package com.chitranjan.pdfdesk

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import com.github.barteksc.pdfviewer.PDFView
import com.google.android.material.appbar.MaterialToolbar
import java.io.File

class ReaderActivity : AppCompatActivity() {

    private lateinit var pdfView: PDFView
    private lateinit var tvPage: TextView
    private var currentPage = 0
    private var pageCount = 0
    private var night = false
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_reader)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar.setNavigationOnClickListener { finish() }
        pdfView = findViewById(R.id.pdfView)
        tvPage = findViewById(R.id.tvPage)

        // Opened from another app via "Open with"?
        if (intent?.action == Intent.ACTION_VIEW && intent.data != null) {
            val uri: Uri = intent.data!!
            title0 = FileUtils.queryName(this, uri)
            if (!FileUtils.copyUriToWorking(this, uri)) {
                toast("Couldn't open this PDF."); finish(); return
            }
            RecentStore.add(this, uri.toString(), title0)
        } else {
            title0 = intent.getStringExtra("title") ?: "PDF Desk"
        }
        toolbar.title = title0
        buildMenu(toolbar)

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
            .nightMode(night)
            .onPageChange { p, count ->
                currentPage = p
                pageCount = count
                tvPage.text = "${p + 1} / $count"
            }
            .onError { toast("Could not render this PDF.") }
            .load()
    }

    private fun buildMenu(toolbar: MaterialToolbar) {
        val m: Menu = toolbar.menu
        m.clear()
        m.add(0, 1, 0, "Pages")
        m.add(0, 2, 1, "Annotate this page")
        m.add(0, 8, 2, "Edit text on this page")
        m.add(0, 3, 3, "Go to page")
        m.add(0, 4, 4, "Night mode")
        m.add(0, 5, 5, "Merge another PDF")
        m.add(0, 6, 6, "Save / Export")
        m.add(0, 7, 7, "Share")
        toolbar.setOnMenuItemClickListener { onMenu(it) }
    }

    private fun onMenu(item: MenuItem): Boolean {
        when (item.itemId) {
            1 -> { needsReload = true; startActivity(Intent(this, PageManagerActivity::class.java)) }
            2 -> {
                needsReload = true
                startActivity(Intent(this, AnnotateActivity::class.java).putExtra("page", currentPage))
            }
            8 -> {
                needsReload = true
                startActivity(Intent(this, TextEditActivity::class.java).putExtra("page", currentPage))
            }
            3 -> goToPageDialog()
            4 -> { night = !night; load(currentPage); toast(if (night) "Night mode on" else "Night mode off") }
            5 -> pickMerge.launch(arrayOf("application/pdf"))
            6 -> exportDoc.launch(sanitizedExportName())
            7 -> share()
            else -> return false
        }
        return true
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

    private fun sanitizedExportName(): String {
        val base = title0.removeSuffix(".pdf").ifBlank { "document" }
        return "${base}_edited.pdf"
    }

    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_SHORT).show()
}
