package com.chitranjan.pdfdesk

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import java.util.concurrent.Executors

/**
 * Reading mode: the PDF's text, reflowed to the screen width. No zooming,
 * no side-scrolling — adjustable type size instead. Text-layer PDFs only.
 */
class ReaderModeActivity : AppCompatActivity() {

    private var sizeSp = 17f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_reader_mode)

        findViewById<MaterialToolbar>(R.id.toolbar).setNavigationOnClickListener { finish() }
        val body = findViewById<TextView>(R.id.tvBody)

        findViewById<TextView>(R.id.btnSmaller).setOnClickListener {
            sizeSp = (sizeSp - 1.5f).coerceAtLeast(12f); body.textSize = sizeSp
        }
        findViewById<TextView>(R.id.btnLarger).setOnClickListener {
            sizeSp = (sizeSp + 1.5f).coerceAtMost(30f); body.textSize = sizeSp
        }

        val ui = Handler(Looper.getMainLooper())
        Executors.newSingleThreadExecutor().execute {
            val text = try { PdfEditor.extractText(FileUtils.workingFile(this)) } catch (e: Exception) { "" }
            ui.post {
                body.text = if (text.isBlank())
                    "This PDF has no text layer to reflow — scanned pages can't be shown in reading mode."
                else text
            }
        }
    }
}
