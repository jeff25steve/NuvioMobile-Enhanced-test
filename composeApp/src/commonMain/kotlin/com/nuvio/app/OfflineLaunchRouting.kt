package com.nuvio.app

import com.nuvio.app.core.network.NetworkCondition

internal fun shouldResolveInitialLaunchRoute(
    initialHomeReady: Boolean,
    offlineLaunchRouteHandled: Boolean,
    downloadsLoaded: Boolean,
    networkCondition: NetworkCondition,
): Boolean =
    initialHomeReady &&
        !offlineLaunchRouteHandled &&
        downloadsLoaded &&
        networkCondition != NetworkCondition.Unknown &&
        networkCondition != NetworkCondition.Checking
