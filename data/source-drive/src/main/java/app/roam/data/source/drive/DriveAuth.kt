package app.roam.data.source.drive

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Task
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Google Drive authorisation.
 *
 * Scope is the FULL `.../auth/drive`. `drive.file` only ever sees files the app
 * itself created, so it cannot enumerate an existing Music/ tree nor create
 * folders inside it. That makes this a *restricted* scope -- set the Cloud
 * Console consent screen to "In production" and do NOT submit for verification.
 * Testing status expires refresh tokens after seven days.
 */
@Singleton
class DriveAuth @Inject constructor(
    @ApplicationContext private val ctx: Context,
) {
    /** What a call to [authorize] produced. */
    sealed interface Outcome {
        data class Granted(val accessToken: String) : Outcome
        /** First run, or scopes changed. Launch this, then call authorize again. */
        data class NeedsConsent(val pendingIntent: PendingIntent) : Outcome
        data class Failed(val cause: Throwable) : Outcome
    }

    private val mutex = Mutex()
    @Volatile private var cachedToken: String? = null
    @Volatile private var cachedAt: Long = 0

    val isConnected: Boolean get() = cachedToken != null

    suspend fun authorize(): Outcome = mutex.withLock {
        try {
            val request = AuthorizationRequest.builder()
                .setRequestedScopes(listOf(Scope(SCOPE_DRIVE)))
                .build()

            val result = Identity.getAuthorizationClient(ctx).authorize(request).await()

            if (result.hasResolution()) {
                val intent = result.pendingIntent
                    ?: return Outcome.Failed(IllegalStateException("Resolution required but no PendingIntent"))
                Outcome.NeedsConsent(intent)
            } else {
                val token = result.accessToken
                    ?: return Outcome.Failed(IllegalStateException("Authorized but no access token"))
                cachedToken = token
                cachedAt = System.currentTimeMillis()
                Outcome.Granted(token)
            }
        } catch (t: Throwable) {
            Outcome.Failed(t)
        }
    }

    /** Call after the consent PendingIntent returns, with its result Intent. */
    suspend fun completeConsent(data: Intent?): Outcome = mutex.withLock {
        try {
            val result: AuthorizationResult =
                Identity.getAuthorizationClient(ctx).getAuthorizationResultFromIntent(data)
            val token = result.accessToken
                ?: return Outcome.Failed(IllegalStateException("Consent returned no access token"))
            cachedToken = token
            cachedAt = System.currentTimeMillis()
            Outcome.Granted(token)
        } catch (t: Throwable) {
            Outcome.Failed(t)
        }
    }

    /**
     * A token for the next request.
     *
     * AuthorizationResult does not expose an expiry, so this refreshes on a
     * conservative TTL rather than trusting a cached value indefinitely. Play
     * Services returns a cached token cheaply when it is still valid, so the
     * re-authorize call is not expensive.
     */
    suspend fun accessToken(): String? {
        val age = System.currentTimeMillis() - cachedAt
        cachedToken?.let { if (age < TOKEN_TTL_MS) return it }
        return when (val outcome = authorize()) {
            is Outcome.Granted -> outcome.accessToken
            else -> null
        }
    }

    /** Call on a 401 so the next request re-authorises rather than retrying a dead token. */
    suspend fun invalidate() = mutex.withLock {
        cachedToken = null
        cachedAt = 0
    }

    companion object {
        const val SCOPE_DRIVE = "https://www.googleapis.com/auth/drive"
        /** Google access tokens last an hour; refresh well inside that. */
        private const val TOKEN_TTL_MS = 45L * 60 * 1000
    }
}

/**
 * Task -> suspend, without pulling in kotlinx-coroutines-play-services for the
 * two call sites that need it.
 */
private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
    addOnSuccessListener { cont.resume(it) }
    addOnFailureListener { cont.resumeWithException(it) }
    addOnCanceledListener { cont.cancel() }
}
