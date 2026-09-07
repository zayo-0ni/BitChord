package com.music.bitchord.playback

import androidx.media3.common.Player
import com.music.bitchord.data.TrackLog
import com.music.bitchord.data.YtMusicRepository
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.asAudioVersionOf
import com.music.bitchord.data.model.durationMillis
import com.music.bitchord.data.settings.AppSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Prefer a verified audio release and its cover; leave video-only tracks unchanged. */
object AutoAudioVersion {
    private const val TAG = "AutoAudio"
    private const val MAX_ATTEMPTS = 3
    private const val LOOKAHEAD = 12
    private const val PARALLELISM = 3
    private const val RETRY_DELAY_MS = 2_000L
    private const val RETRY_AFTER_MS = 60_000L
    private const val MAX_CACHE = 128

    private data class Attempt(val count: Int, val at: Long)
    private val attempts = linkedMapOf<String, Attempt>()
    private val resolved = linkedMapOf<String, Song>()
    private var job: Job? = null
    private var activePlayer: Player? = null
    private var currentLookup: String? = null

    /** Tell the service that replacement is the same listening event. */
    var onCurrentSwap: ((String) -> Unit)? = null

    fun sweep(player: Player, scope: CoroutineScope, urgent: Boolean = false) {
        if (!AppSettings.autoAudioVersion.value) return
        val current = player.currentMediaItem?.mediaId
        if (job?.isActive == true) {
            if (activePlayer === player &&
                (!urgent || current == currentLookup || pendingAt(player, player.currentMediaItemIndex) == null)
            ) return
            job?.cancel()
        }
        activePlayer = player
        job = scope.launch {
            do {
                convertPending(player)
                val pending = pendingAt(player, player.currentMediaItemIndex) ?: break
                if (pending.videoId !in attempts) break
                delay(RETRY_DELAY_MS)
            } while (AppSettings.autoAudioVersion.value)
        }
    }

    fun reset() {
        job?.cancel()
        job = null
        activePlayer = null
        currentLookup = null
        attempts.clear()
        resolved.clear()
    }

    private suspend fun convertPending(player: Player) {
        val seen = mutableSetOf<String>()
        pendingAt(player, player.currentMediaItemIndex)?.let { song ->
            seen += song.videoId
            currentLookup = song.videoId
            // Never change playWhenReady while searching. Holding it false
            // cannot distinguish a user's Pause from our own temporary hold.
            record(player, song, resolve(song))
        }
        currentLookup = null
        while (AppSettings.autoAudioVersion.value) {
            val start = player.currentMediaItemIndex.coerceAtLeast(0)
            val batch = (start until minOf(player.mediaItemCount, start + LOOKAHEAD))
                .mapNotNull { pendingAt(player, it) }
                .filter { it.videoId !in seen }
                .distinctBy { it.videoId }.take(PARALLELISM)
            if (batch.isEmpty()) break
            seen += batch.map { it.videoId }
            val results = coroutineScope {
                batch.map { song -> async { song to resolve(song) } }.awaitAll()
            }
            results.forEach { (song, result) -> record(player, song, result) }
        }
    }

    private suspend fun resolve(song: Song): Result<Song> {
        resolved[song.videoId]?.let { return Result.success(it) }
        return withContext(Dispatchers.IO) {
            try {
                Result.success(YtMusicRepository.resolveAudio(song))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Result.failure(error)
            }
        }
    }

    private suspend fun record(player: Player, song: Song, result: Result<Song>) {
        currentCoroutineContext().ensureActive()
        if (!AppSettings.autoAudioVersion.value || OriginalVersion.isPinned(song.videoId)) return
        val previous = attempts[song.videoId]?.takeIf { now() - it.at < RETRY_AFTER_MS }
        attempts[song.videoId] = Attempt((previous?.count ?: 0) + 1, now())
        trim(attempts)
        val audio = result.getOrNull()
        if (audio == null || audio.videoId == song.videoId || audio.isVideo || audio.thumbnailUrl.isNullOrBlank()) {
            TrackLog.d(TAG,
                if (result.isFailure) "Audio lookup failed; retaining video (retryable)"
                else "No matching audio release; retaining video", song.videoId)
            return
        }
        resolved[song.videoId] = audio
        trim(resolved)
        // An id can occupy several playlist slots. Update every slot with its
        // own queue metadata; caching success must not strand the duplicates.
        for (index in 0 until player.mediaItemCount) {
            if (player.getMediaItemAt(index).mediaId != song.videoId) continue
            val original = player.getMediaItemAt(index).toSong()
            if (!original.isVideo || OriginalVersion.isPinned(original.videoId)) continue
            replace(player, index, audio.asAudioVersionOf(original))
        }
    }

    private fun pendingAt(player: Player, index: Int): Song? {
        if (index !in 0 until player.mediaItemCount) return null
        val song = player.getMediaItemAt(index).toSong()
        if (song.videoId.isBlank() || !song.isVideo || song.localUri != null || OriginalVersion.isPinned(song.videoId)) return null
        val attempt = attempts[song.videoId]
        if (song.videoId !in resolved && attempt != null &&
            attempt.count >= MAX_ATTEMPTS && now() - attempt.at < RETRY_AFTER_MS
        ) return null
        return song
    }

    /** Shared by automatic conversion and the explicit Audio/Revert control. */
    fun replace(player: Player, index: Int, replacement: Song) {
        val current = index == player.currentMediaItemIndex
        val position = if (current) player.currentPosition else 0L
        if (current) onCurrentSwap?.invoke(replacement.videoId)
        player.replaceMediaItem(index, replacement.toMediaItem())
        if (current) {
            val length = replacement.durationMillis()
            player.seekTo(index, if (length > 0 && position >= length) 0L else position)
        }
    }

    private fun now() = System.nanoTime() / 1_000_000

    private fun <T> trim(map: MutableMap<String, T>) {
        while (map.size > MAX_CACHE) map.remove(map.keys.first())
    }
}
