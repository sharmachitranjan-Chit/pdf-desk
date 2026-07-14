package com.chitranjan.pdfdesk

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private enum class Route { READER, PAGES, MERGE }

    private lateinit var rv: RecyclerView
    private lateinit var tvEmpty: TextView
    private var route = Route.READER

    private val thumbIo = Executors.newFixedThreadPool(2)
    private val ui = Handler(Looper.getMainLooper())
    private val thumbCache = ConcurrentHashMap<String, Bitmap>()

    private val openPdf = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) openUri(uri, persist = true)
    }

    private val pickMergeAdd = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) doMergeSecond(uri) else route = Route.READER
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        findViewById<View>(R.id.tileOpen).setOnClickListener { launchPicker(Route.READER) }
        findViewById<View>(R.id.tilePages).setOnClickListener { launchPicker(Route.PAGES) }
        findViewById<View>(R.id.tileMerge).setOnClickListener { launchPicker(Route.MERGE) }
        findViewById<FloatingActionButton>(R.id.fabOpen).setOnClickListener { launchPicker(Route.READER) }

        rv = findViewById(R.id.rvRecent)
        tvEmpty = findViewById(R.id.tvEmpty)
        rv.layoutManager = LinearLayoutManager(this)
    }

    private fun launchPicker(r: Route) {
        route = r
        openPdf.launch(arrayOf("application/pdf"))
    }

    override fun onResume() {
        super.onResume()
        refreshRecent()
    }

    private fun refreshRecent() {
        val items = RecentStore.get(this)
        tvEmpty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        rv.adapter = RecentAdapter(items, ::loadThumb) { entry ->
            route = Route.READER
            openUri(Uri.parse(entry.uri), persist = false, fromRecent = true)
        }
    }

    private fun openUri(uri: Uri, persist: Boolean, fromRecent: Boolean = false) {
        if (persist) {
            try {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: Exception) { }
        }
        val name = FileUtils.queryName(this, uri)
        val ok = FileUtils.copyUriToWorking(this, uri)
        if (!ok) {
            if (fromRecent) {
                toast("Access to that file expired — pick it again.")
                launchPicker(Route.READER)
            } else toast("Couldn't open that file.")
            return
        }
        RecentStore.add(this, uri.toString(), name, FileUtils.querySize(this, uri))
        when (route) {
            Route.READER -> startActivity(Intent(this, ReaderActivity::class.java).putExtra("title", name))
            Route.PAGES -> startActivity(Intent(this, PageManagerActivity::class.java))
            Route.MERGE -> {
                toast("Now pick the PDF to append.")
                pendingMergeTitle = name
                pickMergeAdd.launch(arrayOf("application/pdf"))
                return
            }
        }
        route = Route.READER
    }

    private var pendingMergeTitle = "document.pdf"

    private fun doMergeSecond(uri: Uri) {
        toast("Merging…")
        val add = File(filesDir, "merge_add.pdf")
        Thread {
            var merged = false
            try {
                contentResolver.openInputStream(uri).use { input ->
                    if (input != null) {
                        add.outputStream().use { out -> input.copyTo(out) }
                        PdfEditor.mergeAppend(FileUtils.workingFile(this), FileUtils.tempFile(this), add)
                        merged = true
                    }
                }
            } catch (e: Exception) { merged = false }
            add.delete()
            runOnUiThread {
                route = Route.READER
                if (merged) {
                    startActivity(
                        Intent(this, ReaderActivity::class.java)
                            .putExtra("title", pendingMergeTitle.removeSuffix(".pdf") + " (merged).pdf")
                    )
                } else toast("Merge failed.")
            }
        }.start()
    }

    /** Render page 0 of a recent's uri as a small thumbnail, cached. */
    private fun loadThumb(entry: RecentEntry, into: ImageView) {
        val key = entry.uri
        thumbCache[key]?.let { into.setImageBitmap(it); return }
        into.setImageResource(R.drawable.ic_doc)
        into.tag = key
        thumbIo.execute {
            val bmp = try {
                contentResolver.openFileDescriptor(Uri.parse(entry.uri), "r")?.use { pfd ->
                    val r = PdfRenderer(pfd)
                    val page = r.openPage(0)
                    val w = 120
                    val h = (w * page.height / page.width.coerceAtLeast(1)).coerceIn(60, 200)
                    val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    b.eraseColor(Color.WHITE)
                    page.render(b, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    page.close(); r.close()
                    b
                }
            } catch (e: Exception) { null }
            if (bmp != null) {
                thumbCache[key] = bmp
                ui.post { if (into.tag == key) into.setImageBitmap(bmp) }
            }
        }
    }

    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_LONG).show()
}

private class RecentAdapter(
    val items: List<RecentEntry>,
    val thumbLoader: (RecentEntry, ImageView) -> Unit,
    val onClick: (RecentEntry) -> Unit
) : RecyclerView.Adapter<RecentAdapter.VH>() {

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val thumb: ImageView = v.findViewById(R.id.imgThumb)
        val name: TextView = v.findViewById(R.id.tvName)
        val meta: TextView = v.findViewById(R.id.tvMeta)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_recent, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val e = items[position]
        holder.name.text = e.name.removeSuffix(".pdf")
        holder.meta.text = buildMeta(e)
        thumbLoader(e, holder.thumb)
        holder.itemView.setOnClickListener { onClick(e) }
    }

    private fun buildMeta(e: RecentEntry): String {
        val parts = StringBuilder("PDF")
        if (e.time > 0) {
            parts.append(" · ").append(
                DateUtils.getRelativeTimeSpanString(e.time, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)
            )
        }
        if (e.size > 0) parts.append(" · ").append(fmtSize(e.size))
        return parts.toString()
    }

    private fun fmtSize(b: Long): String =
        if (b >= 1_000_000) String.format("%.1f MB", b / 1_000_000f)
        else String.format("%.1f KB", b / 1_000f)

    override fun getItemCount(): Int = items.size
}
