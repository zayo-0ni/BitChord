package com.music.bitchord.data

import com.music.bitchord.data.model.SearchResult
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.sources.TrackMatcher
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

/** Resolves an actual catalogue release; a failed search never changes the original video. */
internal class AudioVersionResolver(
    private val counterpart: suspend (Song) -> Song?,
    private val search: suspend (String) -> List<SearchResult>,
    private val log: (String, String) -> Unit = { _, _ -> },
    private val timeoutMs: Long = 12_000,
    private val counterpartTimeoutMs: Long = 3_000,
) {
    suspend fun resolve(song: Song): Song {
        if (!song.isVideo || song.localUri != null) return song
        return withTimeoutOrNull(timeoutMs) { lookup(song) }
            ?: throw IOException("Audio lookup timed out; keeping video")
    }

    private suspend fun lookup(song: Song): Song {
        var failure: Exception? = null
        try {
            val paired = withTimeoutOrNull(counterpartTimeoutMs) { Result.success(counterpart(song)) }
            if (paired == null) failure = IOException("Counterpart lookup timed out")
            paired?.getOrNull()?.takeIf { usable(it, song) }?.let {
                log("YouTube paired audio: ${it.videoId}", song.videoId)
                return it
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            failure = error
            log("Counterpart request failed: ${error.javaClass.simpleName}", song.videoId)
        }

        // The same query occurs in several bilingual interpretations. Fetch it
        // once, then compare its candidates against each applicable interpretation.
        val targets = TrackMatcher.aliases(TrackMatcher.targetOf(song))
        val queries = targets.flatMap { target ->
            TrackMatcher.queries(target).map { it to target }
        }.groupBy({ it.first }, { it.second }).entries.take(8)
        val candidates = mutableListOf<Song>()
        for ((query, _) in queries) {
            try {
                candidates += search(query).mapNotNull {
                    when (it) {
                        is SearchResult.Track -> it.song
                        is SearchResult.TopTrack -> it.song
                        is SearchResult.Browse -> null
                    }
                }.filter { usable(it, song) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                failure = error
                log("Search failed: ${error.javaClass.simpleName}", song.videoId)
                continue
            }
            log("q=\"$query\"; ${candidates.distinctBy { it.videoId }.size} audio candidates", song.videoId)
            for (target in targets) {
                TrackMatcher.bestOfficialAudioForVideo(candidates.distinctBy { it.videoId }, target)?.let {
                    log("Matched '${it.title}' by '${it.artist}' (${it.videoId})", song.videoId)
                    return it
                }
            }
        }
        // A transport failure is retryable, not evidence that the release is absent.
        failure?.let { throw it }
        log("No matching catalogue release; keeping video", song.videoId)
        return song
    }

    private fun usable(candidate: Song, original: Song) =
        candidate.videoId.isNotBlank() && candidate.videoId != original.videoId &&
            !candidate.isVideo && !candidate.thumbnailUrl.isNullOrBlank()
}
