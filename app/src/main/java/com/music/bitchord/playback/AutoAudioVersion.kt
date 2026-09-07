package com.music.bitchord.playback

import androidx.media3.common.Player
import com.music.bitchord.data.TrackLog
import com.music.bitchord.data.YtMusicRepository
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.settings.AppSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
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
 * [AppSettings.autoAudioVersion].
 *
 * **A failed lookup is not a verdict.** The first version of this treated one
 * attempt as final: the id went into a permanent set before the search even
 * ran, and a lookup that came back empty — a dropped request, a search that
 * answered with nothing that minute — burned that track for the rest of the
 * session. The listener saw exactly what they see without this class at all,
 * on a track the manual button then converted first try, because by the time
 * they pressed it the network was fine again. So attempts are counted rather
 * than remembered as a yes/no, and a track is only written off after
 * [MAX_ATTEMPTS] of them across separate passes.
 *
 * **The playing track is resolved before it is heard.** Playback is held while
 * that one lookup runs, so a converted track never starts as the video and cuts
 * over mid-phrase; the cost is a second or two of silence before the first note,
 * which is the trade a listener who never wants to hear a music video is asking
 * for. The rest of the queue is resolved behind that, [LOOKAHEAD_PARALLELISM] at
 * a time and without touching playback, and a track becoming current is urgent
 * enough to interrupt a pass working further down the queue — every second spent
 * on entry 14 is a second of music video on entry 3.
 *
 * **What it will not do is guess.** The matching is [YtMusicRepository]'s, left
 * exactly as strict as the manual button's, because the two failure modes are
 * not equal: a track that stays a video is the same track the listener would
 * have had anyway, while a wrong match is a different recording — a live cut, a
 * remix, another song sharing a name — playing under the right title. So a
 * lookup that finds nothing it is sure of changes nothing, and the video's own
 * audio plays.
 *
 * Entries the listener pinned through "Revert to original" are left alone: see
 * [OriginalVersion], where a pin means exactly "the catalogue match is wrong
 * for this song", which is the judgement this class would otherwise overrule.
 */
object AutoAudioVersion {

    private const val TAG = "AutoAudio"

    /**
     * How many separate passes may look for one track's audio release.
     *
     * Above one because a single empty answer does not distinguish "no
     * catalogue cut exists" from "that request failed", and the two want
     * opposite handling. Small, because the ones that really have no audio
     * release should stop costing searches on every skip.
     */
    private const val MAX_ATTEMPTS = 3

    /** Concurrent lookups for queue entries that are not playing yet. */
    private const val LOOKAHEAD_PARALLELISM = 3

    /** Lookups spent per video id, across passes. */
    private val attempts = mutableMapOf<String, Int>()

    /** Ids to stop looking for: converted, or out of attempts. */
    private val settled = mutableSetOf<String>()

    /** The sweep in flight. One at a time: these are network searches. */
    private var job: Job? = null

    /**
     * Told the incoming id just before the *playing* entry is replaced.
     *
     * [PlaybackService] sets this so its transition handler can tell this swap
     * from the queue actually moving on. Media3 reports an in-place replace as
     * a playlist-changed transition, and that path scrobbles, writes history,
     * resubmits to ListenBrainz and closes out a play count — all of it a
     * second time, for a song the listener heard once. The service already
     * guards its own quality swaps this way; a conversion is the same event
     * with a different id arriving, so it is announced the same way.
     */
    var onCurrentSwap: ((String) -> Unit)? = null

    /**
     * Resolves the video entries in [player]'s queue, if any and if enabled.
     *
     * Safe to call on every timeline change. A pass already running is normally
     * left to finish — but [urgent] (a track transition) preempts it when the
     * newly playing entry is itself an unconverted video, because that is the
     * one the listener is hearing right now.
     */
    fun sweep(player: Player, scope: CoroutineScope, urgent: Boolean = false) {
        if (!AppSettings.autoAudioVersion.value) return
        if (job?.isActive == true) {
            if (!urgent || !currentNeedsWork(player)) return
            TrackLog.d(TAG, "current track is an unconverted video; preempting the pass")
            job?.cancel()
        }
        job = scope.launch { convertPending(player) }
    }

    /** Drops the session's record of what has been tried. */
    fun reset() {
        job?.cancel()
        job = null
        attempts.clear()
        settled.clear()
    }

    private fun currentNeedsWork(player: Player): Boolean {
        val index = player.currentMediaItemIndex
        if (index !in 0 until player.mediaItemCount) return false
        return pendingAt(player, index, emptySet()) != null
    }

    private suspend fun convertPending(player: Player) {
        val seen = mutableSetOf<String>()

        // Why this entry was or was not taken up, recorded before anything is
        // decided. A pass that skips a track leaves no other trace, and "no
        // line at all" reads identically to "this code never ran" in a log
        // someone is trying to diagnose from.
        player.currentMediaItemIndex.takeIf { it in 0 until player.mediaItemCount }
            ?.let { index -> player.getMediaItemAt(index) }
            ?.let { item ->
                val song = item.toSong()
                TrackLog.d(
                    TAG,
                    "pass: '${song.title}' isVideo=${song.isVideo} " +
                        "settled=${item.mediaId in settled} " +
                        "attempts=${attempts[item.mediaId] ?: 0}/$MAX_ATTEMPTS " +
                        "pinned=${OriginalVersion.isPinned(item.mediaId)}",
                    item.mediaId,
                )
            }

        // The playing entry is resolved before a note of it is heard, with
        // playback held for as long as that takes. Converting it underneath a
        // track already running is what the swap-and-seek in [apply] exists
        // for, and it is audible — a second or two of the music video, then a
        // cut back to the same place in a different master. Holding trades
        // that for a short wait before playback starts, which is the trade a
        // listener who never wants to hear a music video is asking for.
        firstPending(player)?.let { song ->
            seen += song.videoId
            val resume = player.playWhenReady
            player.playWhenReady = false
            try {
                resolveAndApply(player, song)
            } finally {
                // Restored even when the lookup threw or the pass was
                // preempted: a held player that never gets let go is an app
                // that stopped playing music for no reason the listener can see.
                //
                // Unless the listener already moved it themselves during the
                // hold, in which case theirs is the newer instruction and this
                // has no business overwriting it.
                if (!player.playWhenReady) player.playWhenReady = resume
            }
        }

        while (true) {
            val batch = pendingBatch(player, seen, LOOKAHEAD_PARALLELISM)
            if (batch.isEmpty()) return
            batch.forEach { seen += it.videoId }

            val results = coroutineScope {
                batch.map { song ->
                    async(Dispatchers.IO) {
                        song to runCatching { YtMusicRepository.resolveAudio(song) }.getOrNull()
                    }
                }.awaitAll()
            }

            for ((song, audio) in results) {
                record(player, song, audio)
            }
        }
    }

    /** One lookup, applied if it found anything. Used for the playing entry. */
    private suspend fun resolveAndApply(player: Player, song: Song) {
        val audio = withContext(Dispatchers.IO) {
            runCatching { YtMusicRepository.resolveAudio(song) }.getOrNull()
        }
        record(player, song, audio)
    }

    /**
     * Books the outcome of one lookup and swaps the entry when there was one.
     *
     * The attempt is counted here rather than before the lookup: a pass
     * cancelled by an urgent transition must not spend an attempt on a track it
     * never actually got an answer for.
     */
    private fun record(player: Player, song: Song, audio: Song?) {
        val spent = (attempts[song.videoId] ?: 0) + 1
        attempts[song.videoId] = spent
        if (audio == null || audio.videoId == song.videoId) {
            if (spent >= MAX_ATTEMPTS) {
                settled += song.videoId
                TrackLog.d(
                    TAG,
                    "'${song.title}' has no catalogue cut after $spent tries; keeping video",
                    song.videoId,
                )
            } else {
                TrackLog.d(
                    TAG,
                    "lookup $spent/$MAX_ATTEMPTS found nothing for '${song.title}'; will retry",
                    song.videoId,
                )
            }
            return
        }
        settled += song.videoId
        apply(player, song, audio)
    }

    /** The playing entry, when it is a video still worth resolving. */
    private fun firstPending(player: Player): Song? {
        val count = player.mediaItemCount
        if (count == 0) return null
        val current = player.currentMediaItemIndex.coerceIn(0, count - 1)
        return pendingAt(player, current, emptySet())
    }

    /**
     * Up to [width] entries worth resolving, the playing one first.
     *
     * Read fresh on every slice rather than collected once up front: a lookup
     * takes seconds, and in that time the listener can skip, shuffle, or queue
     * something else.
     */
    private fun pendingBatch(player: Player, seen: Set<String>, width: Int): List<Song> {
        val count = player.mediaItemCount
        if (count == 0) return emptyList()
        val current = player.currentMediaItemIndex.coerceIn(0, count - 1)
        val order = (current until count) + (0 until current)
        val out = mutableListOf<Song>()
        for (index in order) {
            pendingAt(player, index, seen)?.let { out += it }
            if (out.size >= width) break
        }
        return out
    }

    /** The entry at [index] as a [Song], or null when it is not worth a lookup. */
    private fun pendingAt(player: Player, index: Int, seen: Set<String>): Song? {
        val item = player.getMediaItemAt(index)
        val id = item.mediaId
        if (id.isBlank() || id in seen || id in settled) return null
        if ((attempts[id] ?: 0) >= MAX_ATTEMPTS) return null
        if (OriginalVersion.isPinned(id)) return null
        val song = item.toSong()
        return song.takeIf { it.isVideo }
    }

    /**
     * Swaps [audio] in for [song], wherever [song] currently sits.
     *
     * Located by media id at this moment rather than by an index captured
     * before the lookup: several lookups now run at once and the queue can be
     * reordered underneath them, so an index from a few seconds ago may name a
     * different track. A track that has since left the queue is simply gone.
     *
     * [Song.isVideoOrigin] is carried over onto the replacement, exactly as the
     * manual switch does: the track came from a music-video row, and the parts
     * of the app that care — the crossfade controller's own video guard among
     * them — must keep seeing that after the swap.
     */
    private fun apply(player: Player, song: Song, audio: Song) {
        val index = (0 until player.mediaItemCount)
            .firstOrNull { player.getMediaItemAt(it).mediaId == song.videoId }
        if (index == null) {
            TrackLog.d(TAG, "'${song.title}' left the queue during its lookup", song.videoId)
            return
        }
        val isCurrent = index == player.currentMediaItemIndex
        val position = if (isCurrent) player.currentPosition else 0L
        val wasPlaying = player.isPlaying
        TrackLog.d(TAG, "'${song.title}' -> '${audio.title}' (${audio.videoId})", song.videoId)
        if (isCurrent) onCurrentSwap?.invoke(audio.videoId)
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
