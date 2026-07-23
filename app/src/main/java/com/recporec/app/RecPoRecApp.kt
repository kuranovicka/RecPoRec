package com.recporec.app

import android.app.Application
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader

class RecPoRecApp : Application() {
    override fun onCreate() {
        super.onCreate()
        PDFBoxResourceLoader.init(applicationContext)
    }
}
