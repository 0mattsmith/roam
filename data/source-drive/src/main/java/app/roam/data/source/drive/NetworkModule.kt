package app.roam.data.source.drive

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DriveNetworkModule {

    @Provides @Singleton
    fun json(): Json = Json { ignoreUnknownKeys = true; isLenient = true }

    @Provides @Singleton
    fun okHttp(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        // Drive enforces per-user rate limits and a daily download ceiling.
        // Back off on 403 userRateLimitExceeded / 429 rather than hammering.
        .retryOnConnectionFailure(true)
        .build()

    @Provides @Singleton
    fun driveApi(client: OkHttpClient, json: Json): DriveApi =
        Retrofit.Builder()
            .baseUrl("https://www.googleapis.com/")
            .client(
                client.newBuilder()
                    .addInterceptor(DriveAuthInterceptor())
                    .build()
            )
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(DriveApi::class.java)
}

/** TODO(phase1): inject DriveAuth and attach a fresh bearer token per call. */
class DriveAuthInterceptor : okhttp3.Interceptor {
    override fun intercept(chain: okhttp3.Interceptor.Chain): okhttp3.Response = chain.proceed(chain.request())
}
