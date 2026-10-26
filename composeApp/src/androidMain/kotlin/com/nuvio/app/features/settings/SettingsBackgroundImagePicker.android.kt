package com.nuvio.app.features.settings

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private const val MaximumBackgroundImageDimensionPx = 2400

@Composable
internal actual fun rememberSettingsBackgroundImagePicker(
    onImageSelected: (ByteArray) -> Unit,
    onError: () -> Unit,
): SettingsBackgroundImagePicker {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) { readAndNormalizeBackgroundImage(context, uri) }
            }
            result.onSuccess(onImageSelected).onFailure { onError() }
        }
    }

    return remember(launcher) {
        SettingsBackgroundImagePicker {
            launcher.launch(
                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
            )
        }
    }
}

private fun readAndNormalizeBackgroundImage(context: Context, uri: Uri): ByteArray {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    val boundsStream = context.contentResolver.openInputStream(uri)
        ?: error("Unable to read selected image.")
    boundsStream.use { input ->
        BitmapFactory.decodeStream(input, null, bounds)
    }
    require(bounds.outWidth > 0 && bounds.outHeight > 0) { "Unsupported image." }

    var sampleSize = 1
    while (max(bounds.outWidth, bounds.outHeight) / sampleSize > MaximumBackgroundImageDimensionPx) {
        sampleSize *= 2
    }
    val options = BitmapFactory.Options().apply {
        inJustDecodeBounds = false
        inSampleSize = sampleSize
        inPreferredConfig = Bitmap.Config.ARGB_8888
    }
    val source = context.contentResolver.openInputStream(uri)?.use { input ->
        BitmapFactory.decodeStream(input, null, options)
    } ?: error("Unable to decode selected image.")

    val scale = min(
        1f,
        MaximumBackgroundImageDimensionPx / max(source.width, source.height).toFloat(),
    )
    val output = if (scale < 1f) {
        Bitmap.createScaledBitmap(
            source,
            (source.width * scale).roundToInt().coerceAtLeast(1),
            (source.height * scale).roundToInt().coerceAtLeast(1),
            true,
        )
    } else {
        source
    }
    try {
        val stream = ByteArrayOutputStream()
        check(output.compress(Bitmap.CompressFormat.JPEG, 86, stream)) {
            "Unable to encode selected image."
        }
        val bytes = stream.toByteArray()
        require(bytes.isNotEmpty() && bytes.size <= CUSTOM_SETTINGS_BACKGROUND_MAX_IMAGE_BYTES) {
            "Image is too large."
        }
        return bytes
    } finally {
        if (output !== source) output.recycle()
        source.recycle()
    }
}
