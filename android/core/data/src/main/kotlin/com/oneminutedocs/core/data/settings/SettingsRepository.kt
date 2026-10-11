package com.oneminutedocs.core.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.oneminutedocs.core.data.security.TokenCipher
import com.oneminutedocs.domain.Margins
import com.oneminutedocs.domain.PageSize
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

enum class ThemePreference { SYSTEM, LIGHT, DARK }

/** Language used for on-device recognition. Interface language is handled by AppCompat per-app locales. */
enum class RecognitionLanguage { ENGLISH, HINDI }

data class AppSettings(
    val onboardingDone: Boolean = false,
    val theme: ThemePreference = ThemePreference.SYSTEM,
    val defaultPageSize: PageSize = PageSize.A4,
    val defaultMargins: Margins = Margins.NORMAL,
    val recognitionLanguage: RecognitionLanguage = RecognitionLanguage.ENGLISH,
    /** User agreed to upload files for cloud processing and chose "don't ask again". */
    val cloudConsentRemembered: Boolean = false,
)

/** Storage for the anonymous backend client token (encrypted at rest). */
interface ClientTokenStore {
    suspend fun clientToken(): String?
    suspend fun saveClientToken(token: String, expiresAtMs: Long)
    suspend fun clearClientToken()
}

@Singleton
class SettingsRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val cipher: TokenCipher,
) : ClientTokenStore {
    private object Keys {
        val onboarding = booleanPreferencesKey("onboarding_done")
        val theme = stringPreferencesKey("theme")
        val pageSize = stringPreferencesKey("page_size")
        val margins = stringPreferencesKey("margins")
        val recognition = stringPreferencesKey("recognition_language")
        val cloudConsent = booleanPreferencesKey("cloud_consent_remembered")
        val token = stringPreferencesKey("client_token_enc")
        val tokenExpiry = longPreferencesKey("client_token_expiry")
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map { p ->
        AppSettings(
            onboardingDone = p[Keys.onboarding] ?: false,
            theme = enumOr(p[Keys.theme], ThemePreference.SYSTEM),
            defaultPageSize = enumOr(p[Keys.pageSize], PageSize.A4),
            defaultMargins = enumOr(p[Keys.margins], Margins.NORMAL),
            recognitionLanguage = enumOr(p[Keys.recognition], RecognitionLanguage.ENGLISH),
            cloudConsentRemembered = p[Keys.cloudConsent] ?: false,
        )
    }

    suspend fun current(): AppSettings = settings.first()

    suspend fun setOnboardingDone() = context.dataStore.edit { it[Keys.onboarding] = true }
    suspend fun setTheme(t: ThemePreference) = context.dataStore.edit { it[Keys.theme] = t.name }
    suspend fun setDefaultPageSize(s: PageSize) = context.dataStore.edit { it[Keys.pageSize] = s.name }
    suspend fun setDefaultMargins(m: Margins) = context.dataStore.edit { it[Keys.margins] = m.name }
    suspend fun setRecognitionLanguage(l: RecognitionLanguage) = context.dataStore.edit { it[Keys.recognition] = l.name }
    suspend fun setCloudConsentRemembered(v: Boolean) = context.dataStore.edit { it[Keys.cloudConsent] = v }

    /** Returns the stored client token if present and not about to expire. */
    override suspend fun clientToken(): String? {
        val now = System.currentTimeMillis()
        val p = context.dataStore.data.first()
        val expiry = p[Keys.tokenExpiry] ?: return null
        if (expiry - now < 24 * 3600_000L) return null
        return p[Keys.token]?.let { cipher.decrypt(it) }
    }

    override suspend fun saveClientToken(token: String, expiresAtMs: Long) {
        context.dataStore.edit {
            it[Keys.token] = cipher.encrypt(token)
            it[Keys.tokenExpiry] = expiresAtMs
        }
    }

    override suspend fun clearClientToken() {
        context.dataStore.edit {
            it.remove(Keys.token)
            it.remove(Keys.tokenExpiry)
        }
    }

    private inline fun <reified E : Enum<E>> enumOr(v: String?, default: E): E =
        v?.let { runCatching { enumValueOf<E>(it) }.getOrNull() } ?: default
}
