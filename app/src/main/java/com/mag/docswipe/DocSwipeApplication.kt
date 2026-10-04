package com.mag.docswipe

import android.app.Application

class DocSwipeApplication : Application() {
    val database by lazy { DocSwipeDatabase(this) }
}
