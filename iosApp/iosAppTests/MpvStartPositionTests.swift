import XCTest
@testable import Nuvio

final class MpvStartPositionTests: XCTestCase {
    func testFormatsRetryPositionWithStableDecimalSeparator() {
        XCTAssertEqual(mpvRetryStartPositionSeconds(12.3456), "12.346")
        XCTAssertEqual(mpvRetryStartPositionSeconds(0.001), "0.001")
    }

    func testOmitsInvalidOrBeginningPositions() {
        XCTAssertNil(mpvRetryStartPositionSeconds(0))
        XCTAssertNil(mpvRetryStartPositionSeconds(-1))
        XCTAssertNil(mpvRetryStartPositionSeconds(.nan))
        XCTAssertNil(mpvRetryStartPositionSeconds(.infinity))
    }

    func testRetryRequestPreservesExternalMediaAndRequestConfiguration() {
        let request = PendingLoadRequest(
            urlString: "https://media.example/video.m3u8",
            audioUrl: "https://media.example/audio.m4a",
            requestHeaders: ["Authorization": "Bearer test-token"],
            subtitles: [
                PluginSubtitle(
                    url: "https://media.example/subtitles.srt",
                    language: "en",
                    name: "English",
                    headers: ["Referer": "https://media.example"]
                )
            ],
            startPositionSeconds: "10.000",
            queuedAtUptime: 123.0
        )

        let retry = request.retrying(atPositionSeconds: 42.1236)

        XCTAssertEqual(retry.urlString, request.urlString)
        XCTAssertEqual(retry.audioUrl, request.audioUrl)
        XCTAssertEqual(retry.requestHeaders, request.requestHeaders)
        XCTAssertEqual(retry.subtitles.count, 1)
        XCTAssertEqual(retry.subtitles.first?.url, request.subtitles.first?.url)
        XCTAssertEqual(retry.subtitles.first?.language, "en")
        XCTAssertEqual(retry.subtitles.first?.headers?["Referer"], "https://media.example")
        XCTAssertEqual(retry.startPositionSeconds, "42.124")
    }

    func testRetryRequestOmitsStartOptionAtBeginningOfFile() {
        let request = PendingLoadRequest(
            urlString: "https://media.example/video.m3u8",
            audioUrl: nil,
            requestHeaders: [:],
            subtitles: [],
            startPositionSeconds: "10.000",
            queuedAtUptime: 123.0
        )

        XCTAssertNil(request.retrying(atPositionSeconds: 0).startPositionSeconds)
    }

}

final class MpvAudioLanguagePreferenceStateTests: XCTestCase {
    func testStoresNormalizedLanguagePreferencesForCoreRecovery() {
        var state = MpvAudioLanguagePreferenceState()

        XCTAssertNil(state.optionValue)

        state.update([" en ", "", "fr\n"])
        XCTAssertEqual(state.optionValue, "en,fr")
        XCTAssertEqual(state.languages, ["en", "fr"])
    }

    func testAnExplicitEmptyPreferenceRemainsDistinctFromNoPreference() {
        var state = MpvAudioLanguagePreferenceState()
        state.update([])

        XCTAssertEqual(state.languages, [])
        XCTAssertEqual(state.optionValue, "")
    }
    func testApplyingLanguagePreferencesClearsAStaleManualTrackOverride() {
        var state = MpvAudioLanguagePreferenceState()
        state.select(trackId: 7)

        state.update(["en"])

        XCTAssertNil(state.selectedTrackId)
        XCTAssertEqual(state.optionValue, "en")
    }

    func testManualTrackSelectionAfterPreferencesRemainsTheExplicitOverride() {
        var state = MpvAudioLanguagePreferenceState()
        state.update(["en"])
        state.select(trackId: 3)

        XCTAssertEqual(state.selectedTrackId, 3)
        XCTAssertEqual(state.optionValue, "en")
    }

}

final class MpvPendingSubtitleRequestTests: XCTestCase {
    func testQueuedSubtitleDoesNotLeakIntoReplacementSource() {
        var request = MpvPendingSubtitleRequest()
        request.enqueue("https://media.example/old-source.srt")
        request.clearForNewSource()

        XCTAssertNil(request.takeAfterFileLoaded())

        request.enqueue("https://media.example/new-source.srt")
        XCTAssertEqual(
            request.takeAfterFileLoaded(),
            "https://media.example/new-source.srt"
        )
        XCTAssertNil(request.takeAfterFileLoaded())
    }

    func testLatestSubtitleChoiceReplacesEarlierPendingChoice() {
        var request = MpvPendingSubtitleRequest()
        request.enqueue("https://media.example/first.srt")
        request.enqueue("https://media.example/second.srt")

        XCTAssertEqual(
            request.takeAfterFileLoaded(),
            "https://media.example/second.srt"
        )
    }
}


final class MpvExternalSubtitleReplacementPlanTests: XCTestCase {
    func testPlanPreservesSourceMetadataAndRemovesOnlyItsPreviousAutoSyncCopies() {
        let tracks = [
            MpvExternalSubtitleTrackSnapshot(
                id: 2,
                externalFilename: nil,
                title: "Embedded",
                language: "en",
                isExternal: false
            ),
            MpvExternalSubtitleTrackSnapshot(
                id: 7,
                externalFilename: "https://subs.example/english.srt",
                title: "English Forced",
                language: "en",
                isExternal: true
            ),
            MpvExternalSubtitleTrackSnapshot(
                id: 8,
                externalFilename: "/Caches/subtitles/autosync_old.srt",
                title: "English",
                language: "en",
                isExternal: true
            ),
            MpvExternalSubtitleTrackSnapshot(
                id: 9,
                externalFilename: "/Caches/subtitles/autosync_stale.srt",
                title: "English",
                language: "en",
                isExternal: true
            ),
            MpvExternalSubtitleTrackSnapshot(
                id: 10,
                externalFilename: "https://subs.example/french.srt",
                title: "French",
                language: "fr",
                isExternal: true
            ),
        ]

        let plan = makeMpvExternalSubtitleReplacementPlan(
            tracks: tracks,
            replacedUrl: "https://subs.example/english.srt",
            resolvedOriginalPath: nil,
            replacementPath: "/Caches/subtitles/autosync_new.srt"
        )

        XCTAssertEqual(plan?.sourceTrackId, 7)
        XCTAssertNil(plan?.existingReplacementTrackId)
        XCTAssertEqual(plan?.title, "English Forced")
        XCTAssertEqual(plan?.language, "en")
        XCTAssertEqual(plan?.trackIdsToRemove, [7, 8, 9])
    }

    func testPlanMatchesRtlPreparedPathAndReusesAlreadyLoadedReplacement() {
        let tracks = [
            MpvExternalSubtitleTrackSnapshot(
                id: 11,
                externalFilename: "/Caches/rtl-subtitles/english-fixed.srt",
                title: "English SDH",
                language: "en",
                isExternal: true
            ),
            MpvExternalSubtitleTrackSnapshot(
                id: 12,
                externalFilename: "/Caches/subtitles/autosync_current.srt",
                title: "English SDH",
                language: "en",
                isExternal: true
            ),
            MpvExternalSubtitleTrackSnapshot(
                id: 13,
                externalFilename: "/Caches/subtitles/autosync_old.srt",
                title: "English SDH",
                language: "en",
                isExternal: true
            ),
        ]

        let plan = makeMpvExternalSubtitleReplacementPlan(
            tracks: tracks,
            replacedUrl: "https://subs.example/english.srt",
            resolvedOriginalPath: "/Caches/rtl-subtitles/english-fixed.srt",
            replacementPath: "/Caches/subtitles/autosync_current.srt"
        )

        XCTAssertEqual(plan?.sourceTrackId, 11)
        XCTAssertEqual(plan?.existingReplacementTrackId, 12)
        XCTAssertEqual(plan?.title, "English SDH")
        XCTAssertEqual(plan?.language, "en")
        XCTAssertEqual(plan?.trackIdsToRemove, [11, 13])
    }

    func testPlanDoesNotReplaceAnUnmatchedExternalSubtitle() {
        let tracks = [
            MpvExternalSubtitleTrackSnapshot(
                id: 4,
                externalFilename: "https://subs.example/other.srt",
                title: "Other",
                language: "fr",
                isExternal: true
            ),
        ]

        XCTAssertNil(
            makeMpvExternalSubtitleReplacementPlan(
                tracks: tracks,
                replacedUrl: "https://subs.example/missing.srt",
                resolvedOriginalPath: nil,
                replacementPath: "/Caches/subtitles/autosync_new.srt"
            )
        )
    }
}


final class MpvTrackArrayIndexTests: XCTestCase {
    func testAcceptsValidTrackArrayIndices() {
        XCTAssertEqual(mpvTrackArrayIndex(0, count: 3), 0)
        XCTAssertEqual(mpvTrackArrayIndex(2, count: 3), 2)
    }

    func testRejectsNegativeAndOutOfRangeTrackArrayIndices() {
        XCTAssertNil(mpvTrackArrayIndex(-1, count: 3))
        XCTAssertNil(mpvTrackArrayIndex(3, count: 3))
        XCTAssertNil(mpvTrackArrayIndex(0, count: 0))
    }
}
