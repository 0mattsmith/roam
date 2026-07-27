package app.roam.core.datastore

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import app.roam.core.model.CachePolicy
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore by preferencesDataStore("roam_settings")

data class RoamSettings(
    val cachePolicy: CachePolicy = CachePolicy.NextTracks(10),
    val syncOnWifiOnly: Boolean = true,
    val prefetchOnMobile: Boolean = false,
    val lovedMultiplier: Float = 3.0f,
    val recencyDamping: Boolean = true,
    val ignoreArticles: Boolean = true,
    val downloadFormatMp3: Boolean = true,
    val autoUploadToDrive: Boolean = true,
    val filenameTemplate: String = "{track} {title} - {artist}",
    val autoCheckUpdates: Boolean = true,
)

@Singleton
class SettingsRepository @Inject constructor(@ApplicationContext private val ctx: Context) {

    private object K {
        val CACHE_MODE = stringPreferencesKey("cache_mode")        // "tracks" | "bytes"
        val CACHE_VALUE = longPreferencesKey("cache_value")
        val WIFI_ONLY = booleanPreferencesKey("sync_wifi_only")
        val PREFETCH_MOBILE = booleanPreferencesKey("prefetch_mobile")
        val LOVED_MULT = floatPreferencesKey("loved_multiplier")
        val RECENCY = booleanPreferencesKey("recency_damping")
        val ARTICLES = booleanPreferencesKey("ignore_articles")
        val FMT_MP3 = booleanPreferencesKey("download_mp3")
        val UPLOAD = booleanPreferencesKey("auto_upload")
        val TEMPLATE = stringPreferencesKey("filename_template")
        val AUTO_UPDATE = booleanPreferencesKey("auto_check_updates")
    }

    val settings: Flow<RoamSettings> = ctx.dataStore.data.map { p ->
        val mode = p[K.CACHE_MODE] ?: "tracks"
        val value = p[K.CACHE_VALUE] ?: 10L
        RoamSettings(
            cachePolicy = if (mode == "bytes") CachePolicy.StorageBudget(value)
                          else CachePolicy.NextTracks(value.toInt()),
            syncOnWifiOnly = p[K.WIFI_ONLY] ?: true,
            prefetchOnMobile = p[K.PREFETCH_MOBILE] ?: false,
            lovedMultiplier = p[K.LOVED_MULT] ?: 3.0f,
            recencyDamping = p[K.RECENCY] ?: true,
            ignoreArticles = p[K.ARTICLES] ?: true,
            downloadFormatMp3 = p[K.FMT_MP3] ?: true,
            autoUploadToDrive = p[K.UPLOAD] ?: true,
            filenameTemplate = p[K.TEMPLATE] ?: "{track} {title} - {artist}",
            autoCheckUpdates = p[K.AUTO_UPDATE] ?: true,
        )
    }

    suspend fun setCachePolicy(policy: CachePolicy) = ctx.dataStore.edit { p ->
        when (policy) {
            is CachePolicy.NextTracks -> { p[K.CACHE_MODE] = "tracks"; p[K.CACHE_VALUE] = policy.count.toLong() }
            is CachePolicy.StorageBudget -> { p[K.CACHE_MODE] = "bytes"; p[K.CACHE_VALUE] = policy.bytes }
        }
    }

    suspend fun setLovedMultiplier(v: Float) = ctx.dataStore.edit { it[K.LOVED_MULT] = v }
    suspend fun setAutoCheckUpdates(v: Boolean) = ctx.dataStore.edit { it[K.AUTO_UPDATE] = v }
}

@Module
@InstallIn(SingletonComponent::class)
object DataStoreModule
