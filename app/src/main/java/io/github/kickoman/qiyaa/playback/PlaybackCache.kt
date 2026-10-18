package io.github.kickoman.qiyaa.playback

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSink
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheKeyFactory
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import java.io.File
import java.util.concurrent.Executor

@OptIn(UnstableApi::class)
class PlaybackCache(
    private val folder: () -> File,
    private val removals: Executor,
    private val maxBytes: Long = MAX_BYTES,
    private val log: (String) -> Unit = {},
) {
    private val plays = CachedPlays()
    private val holder = lazy { open() }

    fun dataSource(upstream: DataSource): DataSource {
        val cache = holder.value ?: return upstream
        val key = plays.newKey()
        val keyOfPlay =
            CacheKeyFactory { dataSpec ->
                plays.opened(key, dataSpec.uri.toString())
                key
            }
        return CacheDataSource(
            cache,
            upstream,
            CacheFileDataSource(log),
            CacheDataSink(cache, CacheDataSink.DEFAULT_FRAGMENT_SIZE),
            CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR,
            Events(key),
            keyOfPlay,
        )
    }

    fun keepFor(player: Player) {
        if (!holder.isInitialized()) return
        val next = player.nextMediaItemIndex.takeIf { it != C.INDEX_UNSET }?.let(player::getMediaItemAt)
        val keep = listOfNotNull(player.currentMediaItem, next).mapNotNull {
            it.localConfiguration?.uri?.toString()
        }
        removals.execute { drop(keep) }
    }

    fun clear() {
        if (!holder.isInitialized()) return
        removals.execute { drop(emptyList()) }
    }

    private fun drop(keep: List<String>) {
        val cache = holder.value ?: return
        val dropped = plays.toDrop(cache.keys, keep)
        dropped.forEach(cache::removeResource)
        if (dropped.isNotEmpty()) log("Dropped ${dropped.size} plays from the disk")
    }

    private fun open(): SimpleCache? {
        val dir = folder()
        SimpleCache.delete(dir, null)
        val opened = SimpleCache(dir, LeastRecentlyUsedCacheEvictor(maxBytes), null, null, false, false)
        return try {
            opened.checkInitialization()
            opened
        } catch (failed: Cache.CacheException) {
            log("The disk cache is off for this run: ${failed.message}")
            opened.release()
            null
        }
    }

    private inner class Events(private val key: String) : CacheDataSource.EventListener {
        override fun onCachedBytesRead(cacheSizeBytes: Long, cachedBytesRead: Long) = Unit

        override fun onCacheIgnored(reason: Int) {
            if (reason == CacheDataSource.CACHE_IGNORED_REASON_ERROR) {
                log("The disk cache is off for $key after an error")
            }
        }
    }

    companion object {
        const val FOLDER = "playback"
        const val MAX_BYTES = 512L * 1024 * 1024
    }
}
