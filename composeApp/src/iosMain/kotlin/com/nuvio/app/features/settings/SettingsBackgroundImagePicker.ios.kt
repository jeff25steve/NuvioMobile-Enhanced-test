package com.nuvio.app.features.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.PhotosUI.PHPickerConfiguration
import platform.PhotosUI.PHPickerFilter
import platform.PhotosUI.PHPickerResult
import platform.PhotosUI.PHPickerViewController
import platform.PhotosUI.PHPickerViewControllerDelegateProtocol
import platform.UIKit.CGRectMake
import platform.UIKit.CGSizeMake
import platform.UIKit.UIApplication
import platform.UIKit.UIImage
import platform.UIKit.UIImageJPEGRepresentation
import platform.UIKit.UIGraphicsBeginImageContextWithOptions
import platform.UIKit.UIGraphicsEndImageContext
import platform.UIKit.UIGraphicsGetImageFromCurrentImageContext
import platform.UIKit.UIViewController
import platform.darwin.NSObject
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue
import platform.posix.memcpy
import kotlin.math.max
import kotlin.math.min

private const val MaximumBackgroundImageDimensionPx = 2400
private const val MaximumProviderImageBytes = CUSTOM_SETTINGS_BACKGROUND_MAX_IMAGE_BYTES * 4

@Composable
internal actual fun rememberSettingsBackgroundImagePicker(
    onImageSelected: (ByteArray) -> Unit,
    onError: () -> Unit,
): SettingsBackgroundImagePicker {
    val delegateHolder = remember { mutableStateOf<SettingsBackgroundPickerDelegate?>(null) }

    return SettingsBackgroundImagePicker {
        val presenter = topViewController()
        if (presenter == null) {
            onError()
            return@SettingsBackgroundImagePicker
        }

        val configuration = PHPickerConfiguration()
        configuration.filter = PHPickerFilter.imagesFilter
        configuration.selectionLimit = 1
        val picker = PHPickerViewController(configuration = configuration)
        val delegate = SettingsBackgroundPickerDelegate(
            onPicked = { provider ->
                provider.loadDataRepresentationForTypeIdentifier("public.image") { data, _ ->
                    if (data == null || data.length.toLong() !in 1..MaximumProviderImageBytes.toLong()) {
                        dispatch_async(dispatch_get_main_queue()) {
                            delegateHolder.value = null
                            onError()
                        }
                    } else {
                        dispatch_async(dispatch_get_main_queue()) {
                            val result = runCatching { normalizeBackgroundImage(data) }
                            delegateHolder.value = null
                            result.onSuccess(onImageSelected).onFailure { onError() }
                        }
                    }
                }
            },
            onDismissed = { delegateHolder.value = null },
        )
        delegateHolder.value = delegate
        picker.delegate = delegate
        presenter.presentViewController(picker, animated = true, completion = null)
    }
}

private class SettingsBackgroundPickerDelegate(
    private val onPicked: (platform.Foundation.NSItemProvider) -> Unit,
    private val onDismissed: () -> Unit,
) : NSObject(), PHPickerViewControllerDelegateProtocol {
    override fun picker(picker: PHPickerViewController, didFinishPicking: List<*>) {
        picker.dismissViewControllerAnimated(true, completion = null)
        val result = didFinishPicking.firstOrNull() as? PHPickerResult
        val provider = result?.itemProvider
        if (provider == null) {
            onDismissed()
        } else {
            onPicked(provider)
        }
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun normalizeBackgroundImage(data: NSData): ByteArray {
    val image = UIImage(data = data) ?: error("Unsupported image.")
    val maximumPixelDimension = max(image.size.width * image.scale, image.size.height * image.scale)
    require(maximumPixelDimension > 0.0) { "Unsupported image." }
    val ratio = min(1.0, MaximumBackgroundImageDimensionPx / maximumPixelDimension)
    val targetSize = CGSizeMake(image.size.width * ratio, image.size.height * ratio)
    UIGraphicsBeginImageContextWithOptions(targetSize, true, image.scale)
    val outputData = try {
        image.drawInRect(CGRectMake(0.0, 0.0, targetSize.width, targetSize.height))
        val resizedImage = UIGraphicsGetImageFromCurrentImageContext() ?: error("Unable to decode image.")
        UIImageJPEGRepresentation(resizedImage, 0.86) ?: error("Unable to encode image.")
    } finally {
        UIGraphicsEndImageContext()
    }
    val outputSize = outputData.length.toLong()
    require(outputSize in 1..CUSTOM_SETTINGS_BACKGROUND_MAX_IMAGE_BYTES.toLong()) { "Image is too large." }
    return ByteArray(outputSize.toInt()).also { bytes ->
        bytes.usePinned { pinned ->
            memcpy(pinned.addressOf(0), outputData.bytes, outputData.length.convert())
        }
    }
}

private fun topViewController(): UIViewController? {
    var controller = UIApplication.sharedApplication.keyWindow?.rootViewController
    while (controller?.presentedViewController != null) {
        controller = controller.presentedViewController
    }
    return controller
}
