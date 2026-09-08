package com.music.bitchord

import com.music.bitchord.data.model.Song
import com.music.bitchord.download.DownloadAudioSelector
import com.music.bitchord.download.DownloadFolders
import com.music.bitchord.download.DownloadTarget
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class DownloadAudioSelectorTest {
    private val video = Song("video", "Music video", "Uploader", "https://frame", isVideo = true, setVideoId = "playlist-row")
    private val audio = Song("audio", "Song", "Artist", "https://cover", "3:20", albumName = "Album")

    @Test fun `playlist download uses official audio identity cover and credits together`() = runTest {
        val selected = DownloadAudioSelector({ audio }).select(video)
        val track = DownloadFolders.withDetails(selected, DownloadTarget("PL1", "Mix", thumbnailUrl = "https://playlist", playlist = true))
        assertEquals(audio.videoId, track.videoId)
        assertEquals(audio.title, track.title)
        assertEquals(audio.artist, track.artist)
        assertEquals(audio.thumbnailUrl, track.thumbnailUrl)
        assertEquals(audio.albumName, track.albumName)
        assertEquals(audio.durationText, track.durationText)
        assertEquals(video.setVideoId, track.setVideoId)
        assertFalse(track.isVideo)
        assertEquals(video, track.originalVideo)
    }

    @Test fun `video only and incomplete matches preserve original`() = runTest {
        for (match in listOf(video, audio.copy(thumbnailUrl = null), audio.copy(isVideo = true))) {
            assertSame(video, DownloadAudioSelector({ match }).select(video))
        }
    }

    @Test fun `lookup failure does not fail the whole playlist download`() = runTest {
        assertSame(video, DownloadAudioSelector({ throw IOException("offline") }).select(video))
    }

    @Test fun `cancelling a download also cancels its audio lookup`() = runTest {
        try {
            DownloadAudioSelector({ throw CancellationException("cancel") }).select(video)
            fail("Cancellation must propagate")
        } catch (_: CancellationException) { }
    }

    @Test fun `audio local files and explicit original choice bypass lookup`() = runTest {
        val selector = DownloadAudioSelector({ error("Unexpected lookup") })
        assertSame(audio, selector.select(audio))
        val local = video.copy(localUri = "file:///song.m4a")
        assertSame(local, selector.select(local))
        assertSame(video, selector.select(video, enabled = false))
        assertSame(video, selector.select(video, pinned = true))
    }
}
