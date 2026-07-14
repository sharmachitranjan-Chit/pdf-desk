package com.chitranjan.pdfdesk

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File

/**
 * All screens operate on ONE shared working copy in internal storage
 * (filesDir/working.pdf). Opening copies the chosen document in; saving/
 * exporting copies the working file back out to a user-chosen location.
 */
object FileUtils {

    fun workingFile(ctx: Context): File = File(ctx.filesDir, "working.pdf")
    fun tempFile(ctx: Context): File = File(ctx.filesDir, "temp_out.pdf")

    fun copyUriToWorking(ctx: Context, uri: Uri): Boolean = try {
        ctx.contentResolver.openInputStream(uri).use { input ->
            if (input == null) return false
            workingFile(ctx).outputStream().use { out -> input.copyTo(out) }
        }
        true
    } catch (e: Exception) {
        false
    }

    fun copyWorkingToUri(ctx: Context, uri: Uri): Boolean = try {
        ctx.contentResolver.openOutputStream(uri, "wt").use { out ->
            if (out == null) return false
            workingFile(ctx).inputStream().use { input -> input.copyTo(out) }
        }
        true
    } catch (e: Exception) {
        false
    }

    fun copyFileToUri(ctx: Context, src: File, uri: Uri): Boolean = try {
        ctx.contentResolver.openOutputStream(uri, "wt").use { out ->
            if (out == null) return false
            src.inputStream().use { input -> input.copyTo(out) }
        }
        true
    } catch (e: Exception) {
        false
    }

    fun queryName(ctx: Context, uri: Uri): String {
        var name = "document.pdf"
        try {
            ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
                val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && c.moveToFirst()) c.getString(idx)?.let { name = it }
            }
        } catch (_: Exception) {
        }
        if (!name.lowercase().endsWith(".pdf")) name = "$name.pdf"
        return name
    }

    fun querySize(ctx: Context, uri: Uri): Long {
        var size = 0L
        try {
            ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
                val idx = c.getColumnIndex(OpenableColumns.SIZE)
                if (idx >= 0 && c.moveToFirst() && !c.isNull(idx)) size = c.getLong(idx)
            }
        } catch (_: Exception) {
        }
        return size
    }
}
