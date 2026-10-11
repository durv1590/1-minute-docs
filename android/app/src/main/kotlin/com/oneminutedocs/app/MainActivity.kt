package com.oneminutedocs.app

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.compose.rememberNavController
import androidx.activity.viewModels
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.oneminutedocs.app.feature.scanner.ScanSessionStore
import com.oneminutedocs.app.navigation.OmdNavHost
import com.oneminutedocs.core.data.settings.AppSettings
import com.oneminutedocs.core.data.settings.SettingsRepository
import com.oneminutedocs.core.data.settings.ThemePreference
import com.oneminutedocs.core.data.work.CleanupWorker
import com.oneminutedocs.core.designsystem.OmdTheme
import com.oneminutedocs.core.designsystem.ThemeMode
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class MainViewModel @Inject constructor(private val settings: SettingsRepository, val scanSessions: ScanSessionStore) : ViewModel() {
    /** Null until preferences are read; the splash screen stays up meanwhile (typically a few milliseconds). */
    val state: StateFlow<AppSettings?> = settings.settings.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    fun onboardingDone() {
        viewModelScope.launch { settings.setOnboardingDone() }
    }
}

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {
    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        super.onCreate(savedInstanceState)
        splash.setKeepOnScreenCondition { vm.state.value == null }
        enableEdgeToEdge()
        CleanupWorker.schedule(applicationContext)
        setContent {
            val settings by vm.state.collectAsStateWithLifecycle()
            val s = settings ?: return@setContent
            // Decide the start destination once; later preference changes must not rebuild the graph.
            val startOnboarding = remember { !s.onboardingDone }
            OmdTheme(themeMode = when (s.theme) { ThemePreference.LIGHT -> ThemeMode.LIGHT; ThemePreference.DARK -> ThemeMode.DARK; else -> ThemeMode.SYSTEM }) {
                Surface {
                    val nav = rememberNavController()
                    OmdNavHost(nav, startOnboarding = startOnboarding, scanSessions = vm.scanSessions, onOnboardingDone = vm::onboardingDone)
                }
            }
        }
    }
}
