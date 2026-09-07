package com.nuegochi.app.draw

import android.graphics.Bitmap
import java.io.File
import java.io.FileOutputStream

object BitmapIO {
    fun save(bitmap: Bitmap, file: File) {
        FileOutputStream(file).use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
    }
}
