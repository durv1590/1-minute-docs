package com.oneminutedocs.app.feature.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.DocumentScanner
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.oneminutedocs.app.R
import com.oneminutedocs.core.designsystem.BrandMark
import kotlinx.coroutines.launch

/** Skippable introduction. It requests no permissions; those are asked for when a feature needs them. */
@Composable
fun OnboardingScreen(onFinish: () -> Unit) {
    val slides = listOf(
        Triple(Icons.Outlined.Build, R.string.onboarding_pdf_title, R.string.onboarding_pdf_body),
        Triple(Icons.Outlined.DocumentScanner, R.string.onboarding_scan_title, R.string.onboarding_scan_body),
        Triple(Icons.Outlined.EditNote, R.string.onboarding_handwriting_title, R.string.onboarding_handwriting_body),
        Triple(Icons.Outlined.AutoAwesome, R.string.onboarding_ai_title, R.string.onboarding_ai_body),
        Triple(Icons.Outlined.SwapHoriz, R.string.onboarding_convert_title, R.string.onboarding_convert_body),
    )
    val pager = rememberPagerState { slides.size }
    val scope = rememberCoroutineScope()
    Scaffold { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                BrandMark(stringResource(R.string.app_name))
                TextButton(onClick = onFinish) { Text(stringResource(R.string.action_skip)) }
            }
            HorizontalPager(state = pager, modifier = Modifier.weight(1f)) { page ->
                val (icon, title, body) = slides[page]
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(Modifier.widthIn(max = 480.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Icon(icon, null, modifier = Modifier.size(72.dp), tint = MaterialTheme.colorScheme.primary)
                        Text(stringResource(title), style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
                        Text(stringResource(body), style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
                    }
                }
            }
            Text(stringResource(R.string.onboarding_page, pager.currentPage + 1, slides.size), style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.size(12.dp))
            Button(
                onClick = { if (pager.currentPage == slides.lastIndex) onFinish() else scope.launch { pager.animateScrollToPage(pager.currentPage + 1) } },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(if (pager.currentPage == slides.lastIndex) R.string.action_get_started else R.string.action_next)) }
        }
    }
}
