package com.chitranjan.pdfdesk

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import java.io.File
import java.util.concurrent.Executors

class PageManagerActivity : AppCompatActivity() {

    private lateinit var rv: RecyclerView
    private val order = ArrayList<Int>()            // current-file page indices, display order
    private val selected = HashSet<Int>()           // selected positions (== page index when not mid-drag)
    private val cache = HashMap<Int, Bitmap>()
    private val io = Executors.newSingleThreadExecutor()
    private val ui = Handler(Looper.getMainLooper())
    private lateinit var adapter: PageAdapter

    private var pendingExtract: List<Int> = emptyList()
    private var pendingSplit = -1

    private val extractTo = registerForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        val sel = pendingExtract
        if (uri != null && sel.isNotEmpty()) io.execute {
            val out = File(filesDir, "extract_out.pdf")
            var ok = false
            try { PdfEditor.extractPages(work(), out, sel); ok = FileUtils.copyFileToUri(this, out, uri) } catch (_: Exception) {}
            out.delete()
            ui.post { toast(if (ok) "Extracted ${sel.size} page(s)." else "Extract failed.") }
        }
    }

    private val splitTo = registerForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        val idx = pendingSplit
        if (uri != null && idx >= 0) io.execute {
            val out1 = File(filesDir, "split_1.pdf")
            val out2 = File(filesDir, "split_2.pdf")
            var ok = false
            try {
                PdfEditor.splitAt(work(), out1, out2, idx)
                ok = FileUtils.copyFileToUri(this, out2, uri)
                if (ok) { if (work().exists()) work().delete(); out1.copyTo(work(), overwrite = true) }
            } catch (_: Exception) {}
            out1.delete(); out2.delete()
            ui.post {
                if (ok) { toast("Split done. First part kept, second part saved."); reload() }
                else toast("Split failed.")
            }
        }
    }

    private val pickMerge = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) io.execute {
            val add = File(filesDir, "pm_merge.pdf")
            var ok = false
            try {
                contentResolver.openInputStream(uri)?.use { inp -> add.outputStream().use { o -> inp.copyTo(o) } }
                PdfEditor.mergeAppend(work(), FileUtils.tempFile(this), add); ok = true
            } catch (_: Exception) {}
            add.delete()
            ui.post { if (ok) { toast("Merged."); reload() } else toast("Merge failed.") }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_page_manager)
        findViewById<MaterialToolbar>(R.id.toolbar).setNavigationOnClickListener { finish() }

        rv = findViewById(R.id.rvPages)
        rv.layoutManager = GridLayoutManager(this, 3)
        adapter = PageAdapter()
        rv.adapter = adapter

        val touch = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN or ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT, 0
        ) {
            override fun onMove(r: RecyclerView, vh: RecyclerView.ViewHolder, t: RecyclerView.ViewHolder): Boolean {
                val from = vh.bindingAdapterPosition; val to = t.bindingAdapterPosition
                if (from < 0 || to < 0) return false
                val m = order.removeAt(from); order.add(to, m)
                adapter.notifyItemMoved(from, to)
                return true
            }
            override fun onSwiped(vh: RecyclerView.ViewHolder, dir: Int) {}
            override fun isLongPressDragEnabled() = true
            override fun clearView(r: RecyclerView, vh: RecyclerView.ViewHolder) {
                super.clearView(r, vh)
                commitReorderIfNeeded()
            }
        })
        touch.attachToRecyclerView(rv)

        findViewById<MaterialButton>(R.id.btnRotateL).setOnClickListener { rotate(-90) }
        findViewById<MaterialButton>(R.id.btnRotateR).setOnClickListener { rotate(90) }
        findViewById<MaterialButton>(R.id.btnDelete).setOnClickListener { deleteSelected() }
        findViewById<MaterialButton>(R.id.btnExtract).setOnClickListener { extractSelected() }
        findViewById<MaterialButton>(R.id.btnSplit).setOnClickListener { splitSelected() }
        findViewById<MaterialButton>(R.id.btnMerge).setOnClickListener { pickMerge.launch(arrayOf("application/pdf")) }

        reload()
    }

    private fun work(): File = FileUtils.workingFile(this)

    private fun reload() {
        io.execute {
            val count = try { PdfEditor.pageCount(work()) } catch (_: Exception) { 0 }
            ui.post {
                order.clear(); for (i in 0 until count) order.add(i)
                selected.clear(); cache.clear()
                adapter.notifyDataSetChanged()
            }
        }
    }

    private fun commitReorderIfNeeded() {
        val identity = order.indices.all { order[it] == it }
        if (identity) return
        val snapshot = ArrayList(order)
        io.execute {
            try { PdfEditor.reorderPages(work(), FileUtils.tempFile(this), snapshot) } catch (_: Exception) {}
            ui.post { reload() }
        }
    }

    private fun currentSelection(): List<Int> = selected.toList().sorted()

    private fun rotate(delta: Int) {
        val targets = if (selected.isEmpty()) order.toList() else currentSelection()
        if (selected.isEmpty()) toast("Rotating all pages")
        io.execute {
            try { PdfEditor.rotatePages(work(), FileUtils.tempFile(this), targets, delta) } catch (_: Exception) {}
            ui.post { cache.clear(); adapter.notifyDataSetChanged() }
        }
    }

    private fun deleteSelected() {
        val sel = currentSelection()
        if (sel.isEmpty()) { toast("Select pages to delete"); return }
        AlertDialog.Builder(this)
            .setTitle("Delete ${sel.size} page(s)?")
            .setPositiveButton("Delete") { _, _ ->
                io.execute {
                    try { PdfEditor.deletePages(work(), FileUtils.tempFile(this), sel) } catch (_: Exception) {}
                    ui.post { reload() }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun extractSelected() {
        val sel = currentSelection()
        if (sel.isEmpty()) { toast("Select pages to extract"); return }
        pendingExtract = sel
        extractTo.launch("extract.pdf")
    }

    private fun splitSelected() {
        val sel = currentSelection()
        if (sel.size != 1) { toast("Select exactly one page to split after"); return }
        pendingSplit = sel[0]
        AlertDialog.Builder(this)
            .setTitle("Split after page ${sel[0] + 1}?")
            .setMessage("The first part stays open here. Choose where to save the second part.")
            .setPositiveButton("Choose file") { _, _ -> splitTo.launch("part2.pdf") }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun renderThumb(index: Int): Bitmap? = try {
        val pfd = ParcelFileDescriptor.open(work(), ParcelFileDescriptor.MODE_READ_ONLY)
        val r = PdfRenderer(pfd)
        val page = r.openPage(index)
        val w = 330
        val h = (w * page.height.toFloat() / page.width).toInt().coerceAtLeast(1)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(Color.WHITE)
        page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
        page.close(); r.close(); pfd.close()
        bmp
    } catch (e: Exception) { null }

    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_SHORT).show()

    inner class PageAdapter : RecyclerView.Adapter<PageAdapter.VH>() {
        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val card: MaterialCardView = v as MaterialCardView
            val img: ImageView = v.findViewById(R.id.imgThumb)
            val sel: View = v.findViewById(R.id.selOverlay)
            val num: TextView = v.findViewById(R.id.tvNum)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_page_thumb, parent, false)
            return VH(v)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val pageIndex = order[position]
            holder.num.text = (position + 1).toString()
            holder.sel.visibility = if (selected.contains(pageIndex)) View.VISIBLE else View.GONE
            holder.card.strokeWidth = if (selected.contains(pageIndex)) 6 else 0
            holder.card.setStrokeColor(Color.parseColor("#D8A43C"))

            val cached = cache[pageIndex]
            if (cached != null) {
                holder.img.setImageBitmap(cached)
            } else {
                holder.img.setImageBitmap(null)
                io.execute {
                    val bmp = renderThumb(pageIndex)
                    if (bmp != null) ui.post {
                        cache[pageIndex] = bmp
                        val pos = holder.bindingAdapterPosition
                        if (pos != RecyclerView.NO_POSITION) notifyItemChanged(pos)
                    }
                }
            }

            holder.itemView.setOnClickListener {
                if (selected.contains(pageIndex)) selected.remove(pageIndex) else selected.add(pageIndex)
                notifyItemChanged(holder.bindingAdapterPosition)
            }
        }

        override fun getItemCount(): Int = order.size
    }
}
