package com.example.contadordebirras.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.UUID

object ImageUtils {

    fun createTempCameraUri(context: Context): Uri {
        val imagePath = File(context.cacheDir, "images")
        if (!imagePath.exists()) {
            imagePath.mkdirs()
        }
        val tempFile = File(imagePath, "camera_${UUID.randomUUID()}.jpg")
        return FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            tempFile
        )
    }

    fun compressAndSaveImage(context: Context, sourceUri: Uri): Uri? {
        return try {
            val inputStream: InputStream? = context.contentResolver.openInputStream(sourceUri)
            val originalBitmap = BitmapFactory.decodeStream(inputStream)
            inputStream?.close()

            if (originalBitmap == null) return null

            // Calculate new dimensions (max 1024x1024)
            val maxWidth = 1024
            val maxHeight = 1024
            var width = originalBitmap.width
            var height = originalBitmap.height

            if (width > maxWidth || height > maxHeight) {
                val ratioBitmap = width.toFloat() / height.toFloat()
                val ratioMax = maxWidth.toFloat() / maxHeight.toFloat()

                var finalWidth = maxWidth
                var finalHeight = maxHeight
                if (ratioMax > ratioBitmap) {
                    finalWidth = (maxHeight.toFloat() * ratioBitmap).toInt()
                } else {
                    finalHeight = (maxWidth.toFloat() / ratioBitmap).toInt()
                }
                
                width = finalWidth
                height = finalHeight
            }

            val resizedBitmap = Bitmap.createScaledBitmap(originalBitmap, width, height, true)

            // Save to internal storage filesDir
            val internalImagesPath = File(context.filesDir, "images")
            if (!internalImagesPath.exists()) {
                internalImagesPath.mkdirs()
            }
            
            val compressedFile = File(internalImagesPath, "beer_${UUID.randomUUID()}.jpg")
            val outputStream = FileOutputStream(compressedFile)
            
            // Compress
            resizedBitmap.compress(Bitmap.CompressFormat.JPEG, 80, outputStream)
            outputStream.flush()
            outputStream.close()

            originalBitmap.recycle()
            if (originalBitmap != resizedBitmap) {
                resizedBitmap.recycle()
            }

            Uri.fromFile(compressedFile)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}
