package com.music.bitchord

import com.music.bitchord.data.model.Song
import com.music.bitchord.data.sources.TrackMatcher
import org.junit.Assert.*
import org.junit.Test

class OfficialAudioMatcherTest {
    private fun audio(title: String, artist: String = "Artist", duration: String = "3:00") =
        Song("audio", title, artist, "https://example.com/cover.jpg", duration)
    private fun target(title: String, artist: String = "Artist") =
        TrackMatcher.Target(title, artist, 210, isVideo = true)
    private fun match(candidate: Song, target: TrackMatcher.Target): Song? =
        TrackMatcher.aliases(target).firstNotNullOfOrNull {
            TrackMatcher.bestOfficialAudioForVideo(listOf(candidate), it)
        }

    @Test fun `Arabic diacritics tatweel and alef spelling retain the same song`() {
        val song = audio("أنتَ العشق", "محمود التركي")
        assertEquals(song, match(song, target("محمود التركي - انـت العشق (Official Video)", "AlNojomia")))
    }
    @Test fun `bilingual channel upload finds its Arabic release`() {
        val song = audio("علاج عيوني", "محمود التركي")
        assertEquals(song, match(song, target(
            "Mahmoud El Turky - Ilaj 3youni (Official Video) 2026 | محمود التركي - علاج عيوني", "AlNojomia")))
    }
    @Test fun `title followed by artist is not mistaken for artist followed by title`() {
        val song = audio("علاج عيوني", "محمود التركي")
        assertEquals(song, match(song, target("علاج عيوني - محمود التركي", "AlNojomia")))
    }
    @Test fun `with is part of a title and not an unmarked guest credit`() {
        assertNull(match(audio("Dancing"), target("Dancing with Myself")))
        assertEquals("dancing with myself", TrackMatcher.searchableTitle("Dancing with Myself"))
    }
    @Test fun `a real year in a title cannot be stripped to match another song`() {
        assertNull(match(audio("Summer"), target("Summer 2020")))
        assertEquals(audio("1989"), match(audio("1989"), target("Artist - 1989")))
    }
    @Test fun `unknown artist cannot establish an official pairing`() {
        for (artist in listOf("", "Unknown artist", "Unknown", "Various Artists")) {
            assertNull(TrackMatcher.bestOfficialAudioForVideo(listOf(audio("Track", artist)), target("Track", artist)))
        }
    }
    @Test fun `another video is never accepted as the audio release`() {
        assertNull(match(audio("Track").copy(isVideo = true), target("Track")))
    }
    @Test fun `clean and explicit editions cannot cross the video switch`() {
        assertNull(match(audio("Track").copy(isExplicit = false), target("Track").copy(isExplicit = true)))
    }
    @Test fun `new featured guest cannot be introduced or removed`() {
        assertNull(match(audio("Track (feat. Guest)"), target("Track")))
        assertNull(match(audio("Track"), target("Track (feat. Guest)")))
        val song = audio("Track", "Artist, Guest")
        assertEquals(song, match(song, target("Track (feat. Guest)")))
    }
    @Test fun `live suffix on a bilingual segment cannot disappear in another alias`() {
        assertNull(match(audio("Track"), target("Artist - Track | Track (Live)")))
    }
    @Test fun `video intro may be long but audio cannot be an hour loop`() {
        val song = audio("Track")
        assertEquals(song, match(song, target("Track").copy(durationSec = 391)))
        assertNull(match(audio("Track", duration = "1:00:00"), target("Track")))
    }
    @Test fun `Indic vowel marks distinguish different titles`() {
        assertNull(match(audio("दिन"), target("दान")))
    }
    @Test fun `unrelated singer cannot win on exact video duration`() {
        assertNull(match(audio("Brown Rang", "Lovely", "3:31"),
            target("Brown Rang", "Yo Yo Honey Singh").copy(durationSec = 211)))
    }
}
