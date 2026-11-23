package com.nuvio.app.features.player

/** Media3's SurfaceSyncGroup workaround is only implemented for Compose SurfaceView on API 34. */
internal fun shouldEnableComposeSurfaceSyncWorkaround(sdkInt: Int): Boolean = sdkInt == 34
