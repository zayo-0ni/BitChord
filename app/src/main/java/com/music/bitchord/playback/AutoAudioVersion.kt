package com.music.bitchord.playback

import androidx.media3.common.Player
import com.music.bitchord.data.TrackLog
import com.music.bitchord.data.YtMusicRepository
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.settings.AppSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Puts the catalogue cut of a track in the queue where a music-video upload
 * landed, without the listener having to ask for each one.
 *
 * [YtMusicRepository.resolveAudio] already knows how to find the official
 * audio release behind a video, but it is wired to a single explicit tap — the
 * 🎵 control on the now-playing screen — and its own documentation is emphatic
 * that it is "never part of normal queueing or playback". That is the right
 * default for a substitution the app might get wrong on one track. It is the
 * wrong default for a listener who never wants a music video at all: they end
 * up tapping it song after song, and any entry they don't reach in time plays
 * through as the video, with its 16:9 thumbnail in place of the album art.
 *
 * So the same resolution runs by itself over the whole queue, governed by
 * [AppSettings.autoAudioVersion]. Three things shape how:
 *
 *  - **The queue is never held up for it.** Playback starts on whatever was
 *    queued; entries are swapped underneath as their matches come back. A
 *    conversion that had to finish first would put a network round trip
 *    between a tap and the first note, and a long playlist would pay for every
 *    track in it before playing one.
 *
 *  - **The playing track goes first**, then the rest in queue order, so the
 *    entry that matters soonest is the one resolved soonest.
 *
 *  - **A miss is not an error.** `resolveAudio` returns the song unchanged
 *    when nothing better exists, and that entry simply plays as the video —
 *    the same outcome as before this existed. It is recorded in [attempted] so
 *    the search is not repeated on every timeline change for the rest of the
 *    session.
 *
 * Entries the listener pinned through "Revert to original" are left alone: see
 * [OriginalVersion], where a pin means exactly "the catalogue match is wrong
 * for this song", which is the judgement this class would otherwise overrule.
 */
object AutoAudioVersion {

    private const val TAG = "AutoAudio"

    /**
     * Video ids already resolved once this session, matched or not.
     *
     * Both outcomes belong here. A match is replaced in the queue and will not
     * be seen again; a miss would otherwise be retried on every timeline
     * change — a queue holding one unmatchable video would then search for it
     * on every skip, forever.
     */
    private val attempted = mutableSetOf<String>()

    /** The sweep in flight. One at a time: these are network searches. */
    private var job: Job? = null

    /**
     * Resolves the video entries in [player]'s queue, if any and if enabled.
     *
     * Safe to call on every timeline change — a sweep already running is left
     * to finish rather than restarted, and it re-reads the queue between
     * tracks, so entries appended while it works are picked up by the same
     * pass.
     */
    fun sweep(player: Player, scope: CoroutineScope) {
        if (!AppSettings.autoAudioVersion.value) return
        if (job?.isActive == true) return
        job = scope.launch { convertPending(player) }
    }

    /** Drops the session's memory of what has been tried. */
    fun reset() {
        job?.cancel()
        job = null
        attempted.clear()
    }

    private suspend fun convertPending(player: Player) {
        while (true) {
            val (index, song) = nextPending(player) ?: return
            attempted += song.videoId
            val audio = withContext(Dispatchers.IO) {
                runCatching { YtMusicRepository.resolveAudio(song) }.getOrNull()
            }
            if (audio == null || audio.videoId == song.videoId) {
                TrackLog.d(TAG, "no catalogue match for '${song.title}'; keeping video", song.videoId)
                continue
            }
            apply(player, index, song, audio)
        }
    }

    /**
     * The next entry worth resolving, current item first.
     *
     * Read fresh on every iteration rather than collected once up front: a
     * search takes seconds, and in that time the listener can skip, shuffle,
     * or queue something else. An index captured before the search is not
     * necessarily the same entry after it.
     */
    private fun nextPending(player: Player): Pair<Int, Song>? {
        val count = player.mediaItemCount
        if (count == 0) return null
        val current = player.currentMediaItemIndex.coerceIn(0, count - 1)
        val order = (current until count) + (0 until current)
        for (index in order) {
            val item = player.getMediaItemAt(index)
            val id = item.mediaId
            if (id.isBlank() || id in attempted) continue
            if (OriginalVersion.isPinned(id)) continue
            val song = item.toSong()
            if (!song.isVideo) continue
            return index to song
        }
        return null
    }

    /**
     * Swaps [audio] in for [song], having first checked that [index] still
     * holds the entry the search was started for.
     *
     * [Song.isVideoOrigin] is carried over onto the replacement, exactly as the
     * manual switch does: the track came from a music-video row, and the parts
     * of the app that care — the crossfade controller's own video guard among
     * them — must keep seeing that after the swap.
     */
    private fun apply(player: Player, index: Int, song: Song, audio: Song) {
        if (index >= player.mediaItemCount) return
        if (player.getMediaItemAt(index).mediaId != song.videoId) {
            TrackLog.d(TAG, "queue moved during search; '${song.title}' left as is", song.videoId)
            return
        }
        val isCurrent = index == player.currentMediaItemIndex
        val position = if (isCurrent) player.currentPosition else 0L
        val wasPlaying = player.isPlaying
        TrackLog.d(TAG, "'${song.title}' -> '${audio.title}' (${audio.videoId})", song.videoId)
        player.replaceMediaItem(index, audio.copy(isVideoOrigin = true).toMediaItem())
        // Replacing the item the player is on restarts it at zero, which for a
        // track already part-way through is heard as a stutter back to the
        // beginning. Entries further down the queue have no position to keep.
        if (isCurrent) {
            player.seekTo(index, position)
            if (wasPlaying) player.play()
        }
    }
}
