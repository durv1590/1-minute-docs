package com.oneminutedocs.app.common

import com.oneminutedocs.core.data.settings.SettingsRepository
import com.oneminutedocs.core.network.BackendClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

/**
 * Holds a cloud action until the user has seen what will be uploaded and agreed (or has chosen "don't ask again").
 * Owned by a ViewModel; the screen shows [CloudConsentDialog] while [pending] is non-null.
 */
class CloudGate @Inject constructor(private val settings: SettingsRepository, private val backend: BackendClient) {
    private val _pending = MutableStateFlow<(() -> Unit)?>(null)
    val pending: StateFlow<(() -> Unit)?> = _pending.asStateFlow()

    val serverConfigured: Boolean get() = backend.isConfigured

    suspend fun request(action: () -> Unit) {
        if (settings.current().cloudConsentRemembered) action() else _pending.value = action
    }

    suspend fun accept(remember: Boolean) {
        if (remember) settings.setCloudConsentRemembered(true)
        val action = _pending.value
        _pending.value = null
        action?.invoke()
    }

    fun decline() {
        _pending.value = null
    }
}
