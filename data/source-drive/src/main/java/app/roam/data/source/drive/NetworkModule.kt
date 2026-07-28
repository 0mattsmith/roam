package app.roam.data.source.drive

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Response
import retrofit2.Retrofit
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Stamps a fresh bearer token on every Drive call and retries once on 401.
 *
 * runBlocking is acceptable here: OkHttp interceptors run on the call's own
 * background thread, never the main thread.
 */
internal class DriveAuthInterceptor @Inject constructor(
    private val auth: DriveAuth,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val token = runBlocking { auth.accessToken() }
        val signed = chain.request().newBuilder().apply {
            if (token != null) header("Authorization", "Bearer $token")
        }.build()

        val response = chain.proceed(signed)
        if (response.code != 401) return response

        // Token died mid-session. Drop it and try once with a fresh one.
        response.close()
        val fresh = runBlocking { auth.invalidate(); auth.accessToken() }
            ?: return chain.proceed(signed)
        return chain.proceed(
            chain.request().newBuilder().header("Authorization", "Bearer $fresh").build()
        )
    }
}

/**
 * Drive answers 403 userRateLimitExceeded when a client fans out too hard, and
 * the crawl deliberately runs eight requests at once. Without a retry a single
 * throttled response aborts the whole pass, which looks to the user like sync
 * simply not finding their new music.
 */
internal class RateLimitRetryInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        var response = chain.proceed(chain.request())
        var attempt = 0

        while (attempt < MAX_RETRIES && response.isRetryable()) {
            response.close()
            // Exponential with jitter: synchronised retries from eight parallel
            // requests would just re-trigger the same limit together.
            Thread.sleep((1L shl attempt) * 400 + (0..250L).random())
            attempt++
            response = chain.proceed(chain.request())
        }
        return response
    }

    private fun Response.isRetryable(): Boolean = when {
        code == 429 -> true
        code in 500..599 -> true
        // 403 is overloaded: quota errors are transient, permission errors are
        // not. Only the rate-limit reasons are worth retrying.
        code == 403 -> peekBody(2048).string().let {
            "rateLimitExceeded" in it || "userRateLimitExceeded" in it
        }
        else -> false
    }

    private companion object { const val MAX_RETRIES = 4 }
}

@Module
@InstallIn(SingletonComponent::class)
object DriveNetworkModule {

    @Provides @Singleton
    fun json(): Json = Json { ignoreUnknownKeys = true; isLenient = true }

    @Provides @Singleton
    fun okHttp(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .addInterceptor(RateLimitRetryInterceptor())
        .build()

    @Provides @Singleton
    internal fun driveApi(client: OkHttpClient, json: Json, authInterceptor: DriveAuthInterceptor): DriveApi =
        Retrofit.Builder()
            .baseUrl("https://www.googleapis.com/")
            .client(client.newBuilder().addInterceptor(authInterceptor).build())
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(DriveApi::class.java)
}
