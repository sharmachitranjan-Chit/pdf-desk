package com.chitranjan.pdfdesk

import android.content.Context

/**
 * A tiny "recent files" list persisted in SharedPreferences.
 * Rows are uri|name|time|size; old two-field rows still parse (time/size 0).
 */
data class RecentEntry(val uri: String, val name: String, val time: Long = 0L, val size: Long = 0L)

object RecentStore {
    private const val PREF = "pdfdesk_recent"
    private const val KEY = "list"
    private const val MAX = 12
    private const val SEP = "\u0001"
    private const val ROW = "\u0002"

    fun get(ctx: Context): List<RecentEntry> {
        val raw = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(KEY, "") ?: ""
        if (raw.isEmpty()) return emptyList()
        return raw.split(ROW).mapNotNull {
            val p = it.split(SEP)
            when {
                p.size >= 4 -> RecentEntry(p[0], p[1], p[2].toLongOrNull() ?: 0L, p[3].toLongOrNull() ?: 0L)
                p.size == 2 -> RecentEntry(p[0], p[1])
                else -> null
            }
        }
    }

    fun add(ctx: Context, uri: String, name: String, size: Long = 0L) {
        val current = get(ctx).filter { it.uri != uri }.toMutableList()
        current.add(0, RecentEntry(uri, name, System.currentTimeMillis(), size))
        val raw = current.take(MAX).joinToString(ROW) { "${it.uri}$SEP${it.name}$SEP${it.time}$SEP${it.size}" }
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putString(KEY, raw).apply()
    }
}
