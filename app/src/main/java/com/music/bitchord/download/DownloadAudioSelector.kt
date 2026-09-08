package com.music.bitchord.download

import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.asAudioVersionOf
import kotlinx.coroutines.CancellationException

/** Resolve the recording before choosing its filename, stream and embedded metadata. */
internal class DownloadAudioSelector(
    private val resolve: suspend (Song) -> Song,
    private val onFailure: (Exception) -> Unit = {},
) {
    suspend fun select(song: Song, enabled: Boolean = true, pinned: Boolean = false): Song {
        if (!enabled || pinned || !song.isVideo || song.localUri != null) return song
        val audio = try {
            resolve(song)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            onFailure(error)
            return song
        }
        if (audio.videoId.isBlank() || audio.videoId == song.videoId || audio.isVideo || audio.thumbnailUrl.isNullOrBlank()) return song
        return audio.asAudioVersionOf(song)
    }
}
