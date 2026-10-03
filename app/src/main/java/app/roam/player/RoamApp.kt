package app.roam.player

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import app.roam.core.datastore.SettingsRepository
import app.roam.feature.webui.WebService
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class RoamApp : Application(), Configuration.Provider {

    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var settings: SettingsRepository

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    /**
     * Lives as long as the process, which is the point -- there is nothing
     * shorter-lived to hang this on. SupervisorJob so a thrown collector does
     * not take anything else with it.
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate() {
        super.onCreate()
        watchWebServer()
    }

    /**
     * The web server follows its setting, and `:app` is what connects them.
     *
     * Not done from the Settings screen: a UI feature may not depend on
     * another one, and :feature:settings has no business knowing a service
     * exists. More importantly the SETTING is the source of truth, so the
     * server comes back after a process restart without anyone having to
     * re-toggle anything.
     */
    private fun watchWebServer() {
        scope.launch {
            settings.settings
                .map { it.webServerEnabled }
                .distinctUntilChanged()
                .collect { enabled ->
                    // Guarded, because Android forbids starting a foreground
                    // service from the background on API 31+. If the process
                    // woke up for a sync rather than for a person, this throws
                    // and the right answer is to leave the server off until
                    // Roam is next opened -- not to crash the app.
                    runCatching {
                        if (enabled) WebService.start(this@RoamApp)
                        else WebService.stop(this@RoamApp)
                    }
                }
        }
    }
}
