package com.nuvio.app.features.notifications

import kotlinx.coroutines.test.runTest
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.compose_player_episode_code_episode_only
import nuvio.composeapp.generated.resources.compose_player_episode_code_full
import nuvio.composeapp.generated.resources.notifications_episode_release_body_code
import nuvio.composeapp.generated.resources.notifications_episode_release_body_code_title
import nuvio.composeapp.generated.resources.notifications_episode_release_body_generic
import nuvio.composeapp.generated.resources.notifications_episode_release_body_title
import org.jetbrains.compose.resources.getString
import kotlin.test.Test
import kotlin.test.assertEquals

class EpisodeReleaseNotificationBodyTest {
    @Test
    fun `formats code and title with localized resources`() = runTest {
        val code = getString(Res.string.compose_player_episode_code_full, 2, 5)
        val expected = getString(Res.string.notifications_episode_release_body_code_title, code, "The Test Episode")
        assertEquals(expected, buildEpisodeReleaseNotificationBody(2, 5, "The Test Episode"))
    }

    @Test
    fun `formats an episode without a season`() = runTest {
        val code = getString(Res.string.compose_player_episode_code_episode_only, 5)
        val expected = getString(Res.string.notifications_episode_release_body_code, code)
        assertEquals(expected, buildEpisodeReleaseNotificationBody(null, 5, null))
    }

    @Test
    fun `formats title-only metadata`() = runTest {
        val expected = getString(Res.string.notifications_episode_release_body_title, "Special Episode")
        assertEquals(expected, buildEpisodeReleaseNotificationBody(null, null, "  Special Episode  "))
    }

    @Test
    fun `uses generic text when episode details are absent`() = runTest {
        val expected = getString(Res.string.notifications_episode_release_body_generic)
        assertEquals(expected, buildEpisodeReleaseNotificationBody(null, null, null))
    }
}
