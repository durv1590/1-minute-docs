package com.oneminutedocs.app.feature.settings

import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.os.LocaleListCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.oneminutedocs.app.BuildConfig
import com.oneminutedocs.app.R
import com.oneminutedocs.app.common.ToolScaffold
import com.oneminutedocs.app.common.formatSize
import com.oneminutedocs.core.data.DocumentRepository
import com.oneminutedocs.core.data.settings.AppSettings
import com.oneminutedocs.core.data.settings.RecognitionLanguage
import com.oneminutedocs.core.data.settings.SettingsRepository
import com.oneminutedocs.core.data.settings.ThemePreference
import com.oneminutedocs.core.designsystem.SectionHeader
import com.oneminutedocs.domain.Margins
import com.oneminutedocs.domain.PageSize
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(val settings: SettingsRepository, private val documents: DocumentRepository) : ViewModel() {
    val state: StateFlow<AppSettings> = settings.settings.stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    fun theme(t: ThemePreference) {
        viewModelScope.launch { settings.setTheme(t) }
    }
    fun pageSize(s: PageSize) {
        viewModelScope.launch { settings.setDefaultPageSize(s) }
    }
    fun margins(m: Margins) {
        viewModelScope.launch { settings.setDefaultMargins(m) }
    }
    fun recognition(l: RecognitionLanguage) {
        viewModelScope.launch { settings.setRecognitionLanguage(l) }
    }
    fun cloudConsent(v: Boolean) {
        viewModelScope.launch { settings.setCloudConsentRemembered(v) }
    }
    fun clearCache(onDone: (Long) -> Unit) = viewModelScope.launch { onDone(documents.clearTemporaryFiles(0L)) }
}

@Composable
fun SettingsScreen(onBack: () -> Unit, onLegal: (String) -> Unit, vm: SettingsViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val s by vm.state.collectAsStateWithLifecycle()
    var cleared by remember { mutableStateOf<String?>(null) }
    val currentLang = AppCompatDelegate.getApplicationLocales().toLanguageTags().ifBlank { "system" }

    ToolScaffold(stringResource(R.string.settings_title), onBack) {
        SectionHeader(stringResource(R.string.settings_language))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("system" to R.string.settings_language_system, "en" to R.string.settings_language_en, "hi" to R.string.settings_language_hi).forEach { (tag, label) ->
                FilterChip(selected = currentLang.startsWith(tag), onClick = {
                    AppCompatDelegate.setApplicationLocales(if (tag == "system") LocaleListCompat.getEmptyLocaleList() else LocaleListCompat.forLanguageTags(tag))
                }, label = { Text(stringResource(label)) })
            }
        }
        SectionHeader(stringResource(R.string.settings_theme))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ThemePreference.entries.forEach { t ->
                FilterChip(selected = s.theme == t, onClick = { vm.theme(t) }, label = {
                    Text(stringResource(when (t) { ThemePreference.SYSTEM -> R.string.theme_system; ThemePreference.LIGHT -> R.string.theme_light; ThemePreference.DARK -> R.string.theme_dark }))
                })
            }
        }
        SectionHeader(stringResource(R.string.settings_export_defaults))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(PageSize.A4, PageSize.LETTER, PageSize.LEGAL, PageSize.A5).forEach { p -> FilterChip(selected = s.defaultPageSize == p, onClick = { vm.pageSize(p) }, label = { Text(p.name) }) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Margins.entries.forEach { m ->
                FilterChip(selected = s.defaultMargins == m, onClick = { vm.margins(m) }, label = {
                    Text(stringResource(when (m) { Margins.NONE -> R.string.margins_none; Margins.SMALL -> R.string.margins_small; Margins.NORMAL -> R.string.margins_normal; Margins.LARGE -> R.string.margins_large }))
                })
            }
        }
        SectionHeader(stringResource(R.string.settings_ocr))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = s.recognitionLanguage == RecognitionLanguage.ENGLISH, onClick = { vm.recognition(RecognitionLanguage.ENGLISH) }, label = { Text("English") })
            FilterChip(selected = s.recognitionLanguage == RecognitionLanguage.HINDI, onClick = { vm.recognition(RecognitionLanguage.HINDI) }, label = { Text("हिन्दी") })
        }
        Text(stringResource(R.string.settings_ocr_note), style = MaterialTheme.typography.bodySmall)
        SectionHeader(stringResource(R.string.settings_privacy))
        ListItem(
            headlineContent = { Text(stringResource(R.string.settings_cloud_consent)) },
            supportingContent = { Text(stringResource(R.string.settings_cloud_consent_desc)) },
            trailingContent = { Switch(checked = s.cloudConsentRemembered, onCheckedChange = vm::cloudConsent) },
        )
        Text(stringResource(R.string.settings_ai_note), style = MaterialTheme.typography.bodySmall)
        SectionHeader(stringResource(R.string.settings_storage))
        OutlinedButton(onClick = { vm.clearCache { cleared = formatSize(context, it) } }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.home_storage_clean)) }
        cleared?.let { Text(stringResource(R.string.home_cleaned, it)) }
        HorizontalDivider()
        SectionHeader(stringResource(R.string.settings_about))
        Text(stringResource(R.string.settings_version, BuildConfig.VERSION_NAME))
        Text(stringResource(R.string.settings_company))
        listOf(
            "help" to R.string.legal_help,
            "privacy" to R.string.legal_privacy,
            "terms" to R.string.legal_terms,
            "retention" to R.string.legal_retention,
            "licenses" to R.string.legal_licenses,
        ).forEach { (page, label) ->
            ListItem(headlineContent = { Text(stringResource(label)) }, modifier = Modifier.fillMaxWidth().then(Modifier), trailingContent = {
                OutlinedButton(onClick = { onLegal(page) }) { Text(stringResource(R.string.action_open)) }
            })
        }
        OutlinedButton(onClick = {
            val intent = android.content.Intent(android.content.Intent.ACTION_SENDTO, android.net.Uri.parse("mailto:")).putExtra(android.content.Intent.EXTRA_SUBJECT, "One-Minute Docs feedback ${BuildConfig.VERSION_NAME}")
            runCatching { context.startActivity(intent) }
        }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.settings_feedback)) }
    }
}

/** Shows bundled help and legal texts (drafts pending legal review) from assets, chosen by app language. */
@Composable
fun LegalScreen(page: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val lang = if (AppCompatDelegate.getApplicationLocales().toLanguageTags().startsWith("hi") || (AppCompatDelegate.getApplicationLocales().isEmpty && java.util.Locale.getDefault().language == "hi")) "hi" else "en"
    val safePage = page.takeIf { it in setOf("help", "privacy", "terms", "retention", "licenses") } ?: "help"
    val text = remember(safePage, lang) {
        runCatching { context.assets.open("legal/${safePage}_$lang.md").bufferedReader().readText() }
            .recoverCatching { context.assets.open("legal/${safePage}_en.md").bufferedReader().readText() }
            .getOrDefault("")
    }
    ToolScaffold(stringResource(R.string.settings_about), onBack) {
        com.oneminutedocs.app.feature.editor.DocumentPreview(com.oneminutedocs.document.MarkdownLite.parse(text).blocks)
    }
}
