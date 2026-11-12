package com.nuvio.app.core.ui

import coil3.request.ImageRequest

/** Applies the platform-specific bitmap policy required by image pixel inspection. */
internal expect fun ImageRequest.Builder.disableHardwareBitmaps(): ImageRequest.Builder
