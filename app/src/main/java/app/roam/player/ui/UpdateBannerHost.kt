package app.roam.player.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.roam.core.designsystem.UpdateBanner
import app.roam.update.UpdateRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class UpdateBannerViewModel @Inject constructor(
    private val updates: UpdateRepository,
) : ViewModel() {
    val available = updates.available
    val dismissed = updates.dismissed
    val downloadPercent = updates.downloadPercent

    fun install() = viewModelScope.launch { updates.install() }
    fun dismiss() = updates.dismiss()
}

/**
 * Shown across every screen, so an update found on launch is visible wherever
 * the user happens to be rather than only in Settings.
 */
@Composable
fun UpdateBannerHost(vm: UpdateBannerViewModel = hiltViewModel()) {
    val update by vm.available.collectAsStateWithLifecycle()
    val dismissed by vm.dismissed.collectAsStateWithLifecycle()
    val percent by vm.downloadPercent.collectAsStateWithLifecycle()

    val shown = update
    if (shown != null && !dismissed) {
        UpdateBanner(
            versionName = shown.versionName,
            downloadPercent = percent,
            onInstall = { vm.install() },
            onDismiss = vm::dismiss,
        )
    }
}
