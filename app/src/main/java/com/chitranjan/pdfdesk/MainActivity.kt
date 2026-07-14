package com.chitranjan.pdfdesk

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton

class MainActivity : AppCompatActivity() {

    private lateinit var rv: RecyclerView
    private lateinit var tvEmpty: TextView

    private val openPdf = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) openUri(uri, persist = true)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        findViewById<MaterialButton>(R.id.btnOpen).setOnClickListener {
            openPdf.launch(arrayOf("application/pdf"))
        }

        rv = findViewById(R.id.rvRecent)
        tvEmpty = findViewById(R.id.tvEmpty)
        rv.layoutManager = LinearLayoutManager(this)
    }

    override fun onResume() {
        super.onResume()
        refreshRecent()
    }

    private fun refreshRecent() {
        val items = RecentStore.get(this)
        tvEmpty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        rv.adapter = RecentAdapter(items) { entry ->
            openUri(Uri.parse(entry.uri), persist = false)
        }
    }

    private fun openUri(uri: Uri, persist: Boolean) {
        if (persist) {
            try {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: Exception) { }
        }
        val name = FileUtils.queryName(this, uri)
        val ok = FileUtils.copyUriToWorking(this, uri)
        if (!ok) {
            Toast.makeText(this, "Couldn't open that file (access may have expired).", Toast.LENGTH_LONG).show()
            return
        }
        RecentStore.add(this, uri.toString(), name)
        startActivity(Intent(this, ReaderActivity::class.java).putExtra("title", name))
    }
}

private class RecentAdapter(
    val items: List<RecentEntry>,
    val onClick: (RecentEntry) -> Unit
) : RecyclerView.Adapter<RecentAdapter.VH>() {

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val name: TextView = v.findViewById(R.id.tvName)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_recent, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val e = items[position]
        holder.name.text = e.name
        holder.itemView.setOnClickListener { onClick(e) }
    }

    override fun getItemCount(): Int = items.size
}
