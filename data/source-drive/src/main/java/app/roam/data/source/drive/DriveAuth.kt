package app.roam.data.source.drive

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Holds the Drive access token and refreshes it on demand.
 *
 * Scope required is the FULL `https://www.googleapis.com/auth/drive` -- a
 * restricted scope. `drive.file` cannot see files the app did not create, so
 * it cannot enumerate an existing Music/ tree or create folders inside it.
 *
 * Set your Cloud Console OAuth consent screen to "In production" but do NOT
 * submit for verification. Testing status expires refresh tokens after 7 days.
 */
@Singleton
class DriveAuth @Inject constructor(
    @ApplicationContext private val ctx: Context,
) {
    private val mutex = Mutex()
    @Volatile private var token: String? = null
    @Volatile private var expiresAt: Long = 0

    /** TODO(phase1): AuthorizationClient.authorize(AuthorizationRequest) */
    suspend fun accessToken(): String = mutex.withLock {
        val now = System.currentTimeMillis()
        token?.takeIf { now < expiresAt - 60_000 } ?: refresh().also { token = it }
    }

    suspend fun invalidate() = mutex.withLock { token = null }

    private suspend fun refresh(): String {
        TODO("Phase 1: Play Services AuthorizationClient with DriveScopes.DRIVE")
    }

    companion object { const val SCOPE_DRIVE = "https://www.googleapis.com/auth/drive" }
}
