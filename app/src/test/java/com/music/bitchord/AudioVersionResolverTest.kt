package com.music.bitchord

import com.music.bitchord.data.AudioVersionResolver
import com.music.bitchord.data.model.SearchResult
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.asAudioVersionOf
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class AudioVersionResolverTest {
    private val video = Song("video", "Track", "Artist", "https://example.com/frame.jpg", "3:30", isVideo = true)
    private val audio = Song("audio", "Track", "Artist", "https://example.com/cover.jpg", "3:00")

    @Test fun `paired audio is used before text search`() = runTest {
        val resolver = AudioVersionResolver({ audio }, { error("Search must not be used") })
        assertEquals(audio, resolver.resolve(video))
    }
    @Test fun `slow counterpart request leaves time for ordinary search`() = runTest {
        val resolver = AudioVersionResolver({ delay(5_000); null },
            { listOf(SearchResult.Track(audio)) }, counterpartTimeoutMs = 100)
        assertEquals(audio, resolver.resolve(video))
    }
    @Test fun `official top result is not lost when no ordinary row exists`() = runTest {
        val resolver = AudioVersionResolver({ null }, { listOf(SearchResult.TopTrack(audio)) })
        assertEquals(audio, resolver.resolve(video))
    }
    @Test fun `video-only track retains original id image and flags`() = runTest {
        val resolver = AudioVersionResolver({ null }, { emptyList() })
        assertSame(video, resolver.resolve(video))
    }
    @Test fun `video result and missing artwork cannot masquerade as official audio`() = runTest {
        val resolver = AudioVersionResolver({ audio.copy(thumbnailUrl = null) }, {
            listOf(SearchResult.Track(video), SearchResult.Track(audio.copy(thumbnailUrl = null)))
        })
        assertSame(video, resolver.resolve(video))
    }
    @Test fun `transport failure stays distinguishable from no catalogue release`() = runTest {
        val resolver = AudioVersionResolver({ null }, { throw IOException("offline") })
        assertTrue(runCatching { resolver.resolve(video) }.exceptionOrNull() is IOException)
    }
    @Test fun `cancelled lookup is not turned into a match or a permanent miss`() = runTest {
        val resolver = AudioVersionResolver({ throw CancellationException("skip") }, { error("cancelled") })
        assertTrue(runCatching { resolver.resolve(video) }.exceptionOrNull() is CancellationException)
    }
    @Test fun `timeout is bounded and leaves the original untouched`() = runTest {
        val resolver = AudioVersionResolver({ null }, { delay(1_000); emptyList() }, timeoutMs = 100)
        assertTrue(runCatching { resolver.resolve(video) }.exceptionOrNull() is IOException)
        assertEquals("video", video.videoId)
        assertTrue(video.isVideo)
    }
    @Test fun `already audio and local tracks skip network lookups`() = runTest {
        val resolver = AudioVersionResolver({ error("unexpected network") }, { error("unexpected search") })
        assertSame(audio, resolver.resolve(audio))
        val local = video.copy(localUri = "content://local/1")
        assertSame(local, resolver.resolve(local))
    }
    @Test fun `conversion keeps queue metadata and a complete original for Revert`() {
        val original = video.copy(fromAutoplay = true, radioName = "Station", setVideoId = "playlist-slot")
        val result = audio.asAudioVersionOf(original)
        assertEquals(audio.thumbnailUrl, result.thumbnailUrl)
        assertFalse(result.isVideo)
        assertTrue(result.isVideoOrigin)
        assertTrue(result.fromAutoplay)
        assertEquals("Station", result.radioName)
        assertEquals("playlist-slot", result.setVideoId)
        assertEquals(original, result.originalVideo)
        assertNull(result.originalVideo?.originalVideo)
    }
}
