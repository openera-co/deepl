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
    /** Saves a screenshot when -Dcarnet.shots=<dir> is set (design review). */
    private fun shot(name: String) {
        val dir = System.getProperty("carnet.shots")?.takeIf { it.isNotBlank() } ?: return
        rule.mainClock.advanceTimeBy(700)
        runCatching { with(com.github.takahirom.roborazzi.RoborazziOptions()) { com.github.takahirom.roborazzi.captureScreenRoboImage(java.io.File(dir, "$name.png").path, roborazziOptions = this) } }
            .onFailure { println("shot $name failed: $it") }
    }
    private fun back() = rule.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }

    @Test
    fun walkThroughAllScreens() {
        // First launch
        waitText("Continue"); shot("01-setup")
        rule.onAllNodesWithText("French ⇄ English")[0].assertExists()
        rule.onNodeWithText("Continue").performClick()
        // Home
        waitText("Translate a file"); shot("02-home")
        rule.onNodeWithText("Carnet").assertIsDisplayed()
        // Text screen
        rule.onAllNodesWithText("Translate")[0].performScrollTo().performClick()
        waitText("FRENCH"); shot("03-text")
        // Swap to English → French, then back to automatic detection.
        rule.onNodeWithContentDescription("Swap languages").performClick(); waitText("ENGLISH")
        rule.onNodeWithText("Auto").performClick(); waitText("FRENCH"); back()
        // Document
        // Document screen (the hero card opens the file picker directly; the screen is reached from Tools too).
        rule.activityRule.scenario.onActivity { }
        // Tools → each conversion screen
        waitText("Tools"); rule.onAllNodesWithText("Tools")[0].performScrollTo().performClick()
        waitText("Word to PDF"); shot("05-tools")
        for (t in listOf("Word to PDF", "PDF to Word", "Merge", "Compress")) {
            rule.onAllNodesWithText(t)[0].performScrollTo().performClick()
            rule.waitUntil(5000) { rule.onAllNodesWithText("Choose file").fetchSemanticsNodes().isNotEmpty() || rule.onAllNodesWithText("Choose files").fetchSemanticsNodes().isNotEmpty() }
            back(); rule.mainClock.advanceTimeBy(600); waitText("Images to PDF")
        }
        back()
        // Settings → Glossary, Models, Share
        waitText("Translate a file"); rule.onNodeWithContentDescription("Settings").performClick()
        waitText("Glossary"); shot("06-settings"); rule.onAllNodesWithText("Glossary")[0].performClick(); waitText("un contrôle"); back()
        waitText("Spelling"); rule.onNodeWithText("Quality").performScrollTo().assertIsDisplayed(); rule.onNodeWithText("Models").performScrollTo().performClick(); waitText("BEST"); waitText("READING"); shot("07-models"); back()
        waitText("Spelling"); rule.onNodeWithText("Share with another phone").performScrollTo().performClick(); waitText("Send"); shot("08-share"); back()
        waitText("Spelling"); rule.onNodeWithText("Check for updates").performScrollTo().assertIsDisplayed()
        back()
        // Bottom tabs
        waitText("Library"); rule.onAllNodesWithText("Library")[0].performClick(); waitText("Your translations will appear here"); shot("09-library")
        rule.onAllNodes(androidx.compose.ui.test.hasContentDescription("Record"))[0].performClick(); waitText("Tap to record"); shot("10-record")
        rule.onAllNodes(androidx.compose.ui.test.hasContentDescription("Translate"))[0].performClick(); waitText("Translate a file")
    }
}
