package com.nuvio.app.core.ui

import coil3.request.ImageRequest

// Coil uses Skia-backed software images on iOS; Android's hardware bitmap flag is not applicable.
internal actual fun ImageRequest.Builder.disableHardwareBitmaps(): ImageRequest.Builder = this
