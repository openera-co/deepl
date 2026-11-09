package app.carnet.ui

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import app.carnet.Incoming
import app.carnet.data.AppSettings
import app.carnet.ui.screens.ConvertScreen
import app.carnet.ui.screens.DocResultScreen
import app.carnet.ui.screens.DocumentScreen
import app.carnet.ui.screens.GlossaryScreen
import app.carnet.ui.screens.HomeScreen
import app.carnet.ui.screens.JobScreen
import app.carnet.ui.screens.LibraryScreen
import app.carnet.ui.screens.ModelsScreen
import app.carnet.ui.screens.RecordScreen
import app.carnet.ui.screens.ScanResultScreen
import app.carnet.ui.screens.ScanScreen
import app.carnet.ui.screens.SettingsScreen
import app.carnet.ui.screens.SetupScreen
import app.carnet.ui.screens.ShareModelsScreen
import app.carnet.ui.screens.TextScreen
import app.carnet.ui.screens.ToolsScreen
import app.carnet.ui.screens.TranscriptScreen
import android.net.Uri

/** Shared state handed between screens without serialising (e.g. text typed on Home). */
object Handoff {
    var text: String = ""
    var files: List<Uri> = emptyList()
}

private const val D = 280

@Composable
fun AppNav(settings: AppSettings, incoming: MutableState<Incoming?>) {
    val nav = rememberNavController()
    LaunchedEffect(incoming.value) {
        when (val inc = incoming.value) {
            is Incoming.Text -> { Handoff.text = inc.text; nav.navigate("text") { launchSingleTop = true } }
            is Incoming.Files -> { Handoff.files = inc.uris; nav.navigate("document?auto=true") { launchSingleTop = true } }
            null -> {}
        }
        incoming.value = null
    }
    val tabs = setOf("home", "record", "library")
    NavHost(
        navController = nav,
        startDestination = if (settings.setupDone) "home" else "setup",
        modifier = Modifier.fillMaxSize().background(pal.bg),
        enterTransition = {
            if (initialState.destination.route in tabs && targetState.destination.route in tabs) fadeIn(tween(180))
            else slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.Start, tween(D, easing = FastOutSlowInEasing), initialOffset = { it / 4 }) + fadeIn(tween(D))
        },
        exitTransition = {
            if (initialState.destination.route in tabs && targetState.destination.route in tabs) fadeOut(tween(120))
            else slideOutOfContainer(AnimatedContentTransitionScope.SlideDirection.Start, tween(D, easing = FastOutSlowInEasing), targetOffset = { it / 8 }) + fadeOut(tween(D / 2))
        },
        popEnterTransition = { slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.End, tween(D, easing = FastOutSlowInEasing), initialOffset = { it / 8 }) + fadeIn(tween(D)) },
        popExitTransition = { slideOutOfContainer(AnimatedContentTransitionScope.SlideDirection.End, tween(D, easing = FastOutSlowInEasing), targetOffset = { it / 4 }) + fadeOut(tween(D / 2)) },
    ) {
        composable("setup") { SetupScreen(nav) }
        composable("home") { HomeScreen(nav, settings) }
        composable("text") { TextScreen(nav) }
        composable("scan", enterTransition = { scaleIn(tween(D), initialScale = 0.94f) + fadeIn(tween(D)) }, exitTransition = { fadeOut(tween(D)) },
            popExitTransition = { scaleOut(tween(D), targetScale = 0.94f) + fadeOut(tween(D)) }) { ScanScreen(nav, settings) }
        composable("scanResult/{id}", arguments = listOf(navArgument("id") { type = NavType.StringType })) { ScanResultScreen(nav, it.arguments!!.getString("id")!!) }
        composable("document?auto={auto}", arguments = listOf(navArgument("auto") { type = NavType.BoolType; defaultValue = false })) { DocumentScreen(nav, settings, it.arguments!!.getBoolean("auto")) }
        composable("job/{id}", arguments = listOf(navArgument("id") { type = NavType.StringType })) { JobScreen(nav, it.arguments!!.getString("id")!!) }
        composable("docResult/{id}", arguments = listOf(navArgument("id") { type = NavType.StringType })) { DocResultScreen(nav, it.arguments!!.getString("id")!!) }
        composable("tools") { ToolsScreen(nav) }
        composable("convert/{tool}", arguments = listOf(navArgument("tool") { type = NavType.StringType })) { ConvertScreen(nav, it.arguments!!.getString("tool")!!) }
        composable("record") { RecordScreen(nav, settings) }
        composable("transcript/{id}", arguments = listOf(navArgument("id") { type = NavType.StringType })) { TranscriptScreen(nav, it.arguments!!.getString("id")!!) }
        composable("library") { LibraryScreen(nav) }
        composable("settings") { SettingsScreen(nav, settings) }
        composable("models") { ModelsScreen(nav, settings) }
        composable("glossary") { GlossaryScreen(nav, settings) }
        composable("share") { ShareModelsScreen(nav) }
    }
}

/** Navigates to a bottom-bar tab without stacking copies. */
fun NavHostController.tab(route: String) {
    if (route == "scan") { navigate("scan"); return }
    navigate(route) {
        popUpTo("home") { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

/** Opens a library item in its screen. */
fun NavHostController.openItem(item: app.carnet.data.LibraryItem) {
    when (item.type) {
        app.carnet.data.ItemType.SCAN -> navigate("scanResult/${item.id}")
        app.carnet.data.ItemType.RECORDING -> navigate("transcript/${item.id}")
        app.carnet.data.ItemType.TEXT -> { Handoff.text = item.meta["french"] ?: ""; navigate("text") }
        else -> navigate("docResult/${item.id}")
    }
}
