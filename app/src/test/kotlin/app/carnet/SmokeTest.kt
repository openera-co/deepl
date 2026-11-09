package app.carnet

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.printToString
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

/**
 * Launches the real app on the JVM (Robolectric) and walks through every screen.
 * Native engines (ONNX Runtime, ML Kit) aren't available here; screens must still render and navigate.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SmokeTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    private fun waitText(t: String) = try { rule.waitUntil(5000) { rule.onAllNodesWithText(t).fetchSemanticsNodes().isNotEmpty() } } catch (e: Throwable) {
        println("=== waiting for '$t' — screen is:\n" + rule.onRoot().printToString(maxDepth = 30)); throw e }
    private fun back() = rule.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }

    @Test
    fun walkThroughAllScreens() {
        // First launch
        waitText("Continue")
        rule.onAllNodesWithText("French ⇄ English")[0].assertExists()
        rule.onNodeWithText("Continue").performClick()
        // Home
        waitText("Document")
        rule.onNodeWithText("Carnet").assertIsDisplayed()
        // Text screen
        rule.onAllNodesWithText("Translate")[0].performScrollTo().performClick()
        waitText("FRENCH")
        // Swap to English → French, then back to automatic detection.
        rule.onNodeWithContentDescription("Swap languages").performClick(); waitText("ENGLISH")
        rule.onNodeWithText("Auto").performClick(); waitText("FRENCH"); back()
        // Document
        waitText("Document"); rule.onAllNodesWithText("Document")[0].performScrollTo().performClick()
        waitText("Choose file"); rule.onNodeWithText("Bilingual copy").assertIsDisplayed(); back()
        // Tools → each conversion screen
        waitText("Tools"); rule.onAllNodesWithText("Tools")[0].performScrollTo().performClick()
        waitText("Word to PDF")
        for (t in listOf("Word to PDF", "PDF to Word", "Merge", "Compress")) {
            rule.onAllNodesWithText(t)[0].performScrollTo().performClick()
            rule.waitUntil(5000) { rule.onAllNodesWithText("Choose file").fetchSemanticsNodes().isNotEmpty() || rule.onAllNodesWithText("Choose files").fetchSemanticsNodes().isNotEmpty() }
            back(); rule.mainClock.advanceTimeBy(600); waitText("Images to PDF")
        }
        back()
        // Settings → Glossary, Models, Share
        waitText("Document"); rule.onNodeWithContentDescription("Settings").performClick()
        waitText("Glossary"); rule.onAllNodesWithText("Glossary")[0].performClick(); waitText("un contrôle"); back()
        waitText("Spelling"); rule.onAllNodesWithText("Models")[0].performScrollTo().performClick(); waitText("BUILT IN"); waitText("TRANSLATION"); back()
        waitText("Spelling"); rule.onNodeWithText("Share with another phone").performScrollTo().performClick(); waitText("Send"); back()
        waitText("Spelling"); rule.onNodeWithText("Check for updates").performScrollTo().assertIsDisplayed()
        back()
        // Bottom tabs
        waitText("Library"); rule.onAllNodesWithText("Library")[0].performClick(); waitText("Your translations will appear here")
        rule.onAllNodes(androidx.compose.ui.test.hasContentDescription("Record"))[0].performClick(); waitText("Start")
        rule.onAllNodes(androidx.compose.ui.test.hasContentDescription("Translate"))[0].performClick(); waitText("Document")
    }
}
