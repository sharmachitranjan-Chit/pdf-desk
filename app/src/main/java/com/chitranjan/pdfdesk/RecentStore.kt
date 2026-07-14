package com.chitranjan.pdfdesk

import android.content.Context

/** A tiny "recent files" list persisted in SharedPreferences. */
data class RecentEntry(val uri: String, val name: String)

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
            val parts = it.split(SEP)
            if (parts.size == 2) RecentEntry(parts[0], parts[1]) else null
        }
    }

    fun add(ctx: Context, uri: String, name: String) {
        val current = get(ctx).filter { it.uri != uri }.toMutableList()
        current.add(0, RecentEntry(uri, name))
        val trimmed = current.take(MAX)
        val raw = trimmed.joinToString(ROW) { "${it.uri}$SEP${it.name}" }
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putString(KEY, raw).apply()
    }
}
