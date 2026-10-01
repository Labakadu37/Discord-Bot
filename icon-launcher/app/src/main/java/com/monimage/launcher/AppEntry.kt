package com.monimage.launcher

import android.content.ComponentName
import android.graphics.Bitmap
import android.graphics.drawable.Drawable

data class AppEntry(
    val label: String,
    val component: ComponentName,
    val originalIcon: Drawable,
    val styledIcon: Bitmap?,
)
