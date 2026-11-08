package com.nuvio.app.features.addons

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AddonRequestMethodPolicyTest {
    @Test
    fun requestBodiesAreEnabledForMethodsSupportedByBothPlatforms() {
        listOf("POST", "PUT", "PATCH", "DELETE", "post", "delete").forEach { method ->
            assertTrue(addonRequestMethodAllowsBody(method), method)
        }
    }

    @Test
    fun requestBodiesAreDisabledForMethodsWithoutExistingBodySupport() {
        listOf("GET", "HEAD", "OPTIONS", "PROPFIND").forEach { method ->
            assertFalse(addonRequestMethodAllowsBody(method), method)
        }
    }
}
