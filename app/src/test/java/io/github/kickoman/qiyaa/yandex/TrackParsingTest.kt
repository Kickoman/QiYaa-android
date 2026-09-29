package io.github.kickoman.qiyaa.yandex

import io.github.kickoman.qiyaa.support.Spec
import io.github.kickoman.qiyaa.support.SpecJson
import kotlinx.serialization.json.jsonArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackParsingTest {
    @Test
    fun `every tracks fixture parses to the spec's tracks`() {
        val cases = Spec.cases("tracks")
        assertTrue("no tracks fixtures", cases.isNotEmpty())
        for (case in cases) {
            val tracks = Spec.fixture("tracks", case).result().jsonArray.map(TrackParsing::parseTrack)
            assertEquals(
                "tracks/$case",
                SpecJson.expectedTracks(Spec.expected("tracks", case)),
                SpecJson.tracks(tracks),
            )
        }
    }

    @Test
    fun `formatSeconds renders integral seconds without a fraction like QString number`() {
        assertEquals("180", YandexApi.formatSeconds(180000))
        assertEquals("201.5", YandexApi.formatSeconds(201500))
    }
}
