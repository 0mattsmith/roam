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
class DriveAuthInterceptor @Inject constructor(
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
        .build()

    @Provides @Singleton
    fun driveApi(client: OkHttpClient, json: Json, authInterceptor: DriveAuthInterceptor): DriveApi =
        Retrofit.Builder()
            .baseUrl("https://www.googleapis.com/")
            .client(client.newBuilder().addInterceptor(authInterceptor).build())
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(DriveApi::class.java)
}
