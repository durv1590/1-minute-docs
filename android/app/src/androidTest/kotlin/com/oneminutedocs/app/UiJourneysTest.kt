package com.oneminutedocs.app

import android.content.res.Configuration
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.oneminutedocs.app.feature.onboarding.OnboardingScreen
import com.oneminutedocs.app.feature.tools.ToolsScreen
import com.oneminutedocs.core.designsystem.OmdTheme
import com.oneminutedocs.domain.ToolId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale

@RunWith(AndroidJUnit4::class)
class UiJourneysTest {
    @get:Rule val compose = createComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun toolSearchFindsMergeAndOpensIt() {
        var opened: ToolId? = null
        compose.setContent { OmdTheme { ToolsScreen(onBack = {}, onTool = { opened = it }) } }
        compose.onNodeWithText(context.getString(R.string.tools_search_hint)).performTextInput("combine")
        compose.onNodeWithText(context.getString(R.string.tool_merge)).assertIsDisplayed().performClick()
        assertEquals(ToolId.MERGE_PDF, opened)
    }

    @Test fun onboardingCanBeSkipped() {
        var finished = false
        compose.setContent { OmdTheme { OnboardingScreen(onFinish = { finished = true }) } }
        compose.onNodeWithText(context.getString(R.string.action_skip)).performClick()
        assertTrue(finished)
    }

    /** Journey 8 (partial): Hindi resources exist for main screens and render without falling back to English. */
    @Test fun hindiResourcesRender() {
        val config = Configuration(context.resources.configuration).apply { setLocale(Locale("hi")) }
        val hi = context.createConfigurationContext(config)
        listOf(R.string.tools_title, R.string.tool_handwriting, R.string.settings_title, R.string.error_original_safe).forEach { id ->
            assertNotEquals(context.getString(id).takeIf { Locale.getDefault().language == "en" }, hi.getString(id))
        }
        val label = hi.getString(R.string.tool_handwriting)
        assertTrue("expected a Devanagari label, got $label", label.any { it in '\u0900'..'\u097F' })
        compose.setContent { OmdTheme { androidx.compose.material3.Text(label) } }
        compose.onNode(hasText(label)).assertIsDisplayed()
    }
}
