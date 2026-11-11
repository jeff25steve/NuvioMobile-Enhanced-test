package com.nuvio.app.core.ui

import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.memory.MemoryCache
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.UIKit.UIApplicationDidReceiveMemoryWarningNotification

private var memoryWarningObserver: Any? = null

internal actual fun ImageLoader.Builder.configurePlatformImageLoader(): ImageLoader.Builder {
    installMemoryWarningObserver()
    return this
}

internal actual val platformProvidesImageLoader: Boolean = false

private fun installMemoryWarningObserver() {
    if (memoryWarningObserver != null) return
    memoryWarningObserver = NSNotificationCenter.defaultCenter.addObserverForName(
        name = UIApplicationDidReceiveMemoryWarningNotification,
        `object` = null,
        queue = NSOperationQueue.mainQueue,
    ) { _ ->
        clearIosImageMemoryCacheOnWarning(
            SingletonImageLoader.get(PlatformContext.INSTANCE).memoryCache,
        )
    }
}

internal fun clearIosImageMemoryCacheOnWarning(memoryCache: MemoryCache?) {
    memoryCache?.clear()
}
