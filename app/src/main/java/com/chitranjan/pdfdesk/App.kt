package com.chitranjan.pdfdesk

import android.app.Application
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader

/**
 * PDF Desk — a tool made by Chitranjan Sharma.
 * Initialises PDFBox-Android's font/resource loader once for the whole app.
 */
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        PDFBoxResourceLoader.init(applicationContext)
    }
}
