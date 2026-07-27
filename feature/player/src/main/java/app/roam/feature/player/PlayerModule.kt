package app.roam.feature.player

import android.content.Context
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object PlayerModule {

    /**
     * One store, LRU-evicted at the user's budget. Cache is not download:
     * it lives in filesDir, is opaque, and is always evictable.
     *
     * FLAG_IGNORE_CACHE_ON_ERROR means a network hiccup serves from cache
     * rather than failing the track -- this is what "keeps playing out of
     * range" actually depends on.
     */
    @Provides @Singleton
    fun cache(@ApplicationContext ctx: Context): SimpleCache =
        SimpleCache(
            File(ctx.filesDir, "media"),
            LeastRecentlyUsedCacheEvictor(DEFAULT_BUDGET_BYTES),
            StandaloneDatabaseProvider(ctx),
        )

    const val DEFAULT_BUDGET_BYTES = 2L * 1024 * 1024 * 1024
    const val CACHE_FLAGS = CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR
}
