package com.nuvio.app.features.catalog

import kotlinx.coroutines.Job
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CatalogRequestOwnershipTest {
    @Test
    fun `same request key is not enough to accept a superseded job`() {
        val previousJob = Job()
        val currentJob = Job()

        assertTrue(isCurrentCatalogRequest("movie:popular", "movie:popular", currentJob, currentJob))
        assertFalse(isCurrentCatalogRequest("movie:popular", "movie:popular", currentJob, previousJob))
    }

    @Test
    fun `a completed request cannot publish after the active key changes`() {
        val job = Job()

        assertFalse(isCurrentCatalogRequest("movie:trending", "movie:popular", job, job))
    }
}
