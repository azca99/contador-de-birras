content = """package com.example.contadordebirras.ui.components

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import coil.compose.AsyncImage
import com.google.firebase.ktx.Firebase
import com.google.firebase.storage.ktx.storage
import kotlinx.coroutines.tasks.await
import android.util.Log

@Composable
fun SecureFirebaseImage(
    model: String,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Fit
) {
    if (model.startsWith("http") || model.startsWith("content://") || model.startsWith("file://")) {
        AsyncImage(
            model = model,
            contentDescription = contentDescription,
            modifier = modifier,
            contentScale = contentScale
        )
    } else {
        // Assume it's a Storage path
        var bitmap by remember(model) { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
        var error by remember(model) { mutableStateOf(false) }

        LaunchedEffect(model) {
            try {
                val maxDownloadSize = 5L * 1024 * 1024 // 5 MB
                val bytes = Firebase.storage.reference.child(model).getBytes(maxDownloadSize).await()
                val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                bitmap = bmp.asImageBitmap()
            } catch (e: Exception) {
                Log.e("SecureFirebaseImage", "Error downloading image: $model", e)
                error = true
            }
        }

        if (bitmap != null) {
            Image(
                bitmap = bitmap!!,
                contentDescription = contentDescription,
                modifier = modifier,
                contentScale = contentScale
            )
        } else {
            // Optional: Show placeholder or nothing
        }
    }
}
"""
with open('app/src/main/java/com/example/contadordebirras/ui/components/SecureFirebaseImage.kt', 'w') as f:
    f.write(content)
