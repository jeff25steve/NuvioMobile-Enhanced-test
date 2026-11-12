package com.nuvio.app.core.ui

import coil3.request.ImageRequest
import coil3.request.allowHardware

internal actual fun ImageRequest.Builder.disableHardwareBitmaps(): ImageRequest.Builder =
    allowHardware(false)
