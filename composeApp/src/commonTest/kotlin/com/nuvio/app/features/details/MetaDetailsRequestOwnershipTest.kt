package com.nuvio.app.features.details

import kotlinx.coroutines.Job
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MetaDetailsRequestOwnershipTest {
    @Test
    fun `same metadata key cannot authorize a superseded load job`() {
        val previousJob = Job()
        val currentJob = Job()

        assertTrue(isCurrentMetaDetailsLoad("movie:tt123", "movie:tt123", currentJob, currentJob))
        assertFalse(isCurrentMetaDetailsLoad("movie:tt123", "movie:tt123", currentJob, previousJob))
    }

    @Test
    fun `cleared or changed metadata requests cannot publish`() {
        val job = Job()

        assertFalse(isCurrentMetaDetailsLoad(null, "movie:tt123", job, job))
        assertFalse(isCurrentMetaDetailsLoad("movie:tt456", "movie:tt123", job, job))
    }
}
