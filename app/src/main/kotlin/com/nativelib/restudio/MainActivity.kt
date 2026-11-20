package com.nativelib.restudio

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.core.content.IntentCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.nativelib.restudio.ui.JobState
import com.nativelib.restudio.ui.MainViewModel
import com.nativelib.restudio.ui.NavEvent
import com.nativelib.restudio.ui.components.ProgressDialog
import com.nativelib.restudio.ui.components.UpdateDialog
import com.nativelib.restudio.ui.screens.DashboardScreen
import com.nativelib.restudio.ui.screens.ElfAnalyzerScreen
import com.nativelib.restudio.ui.screens.ExtractionScreen
import com.nativelib.restudio.ui.screens.HistoryScreen
import com.nativelib.restudio.ui.screens.LEGAL_NOTICE
import com.nativelib.restudio.ui.screens.SettingsScreen
import com.nativelib.restudio.ui.theme.NativeLibTheme

object BuildConfigInfo {
    fun versionName(context: Context): String = runCatching {
        val pi = context.packageManager.getPackageInfo(context.packageName, 0)
        "${pi.versionName} (${androidx.core.content.pm.PackageInfoCompat.getLongVersionCode(pi)})"
    }.getOrDefault("unknown")
}

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) handleIntent(intent)
        setContent {
            val theme by vm.settings.theme.collectAsStateWithLifecycle()
            NativeLibTheme(theme) {
                Surface(color = MaterialTheme.colorScheme.background) { AppRoot(vm) }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    /** Accepts files shared into the app (SEND / SEND_MULTIPLE) or opened with it (VIEW). */
    private fun handleIntent(intent: Intent?) {
        intent ?: return
        val uris: List<Uri> = when (intent.action) {
            Intent.ACTION_SEND -> listOfNotNull(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))
            Intent.ACTION_SEND_MULTIPLE -> IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
            Intent.ACTION_VIEW -> listOfNotNull(intent.data)
            else -> emptyList()
        }
        if (uris.isNotEmpty()) vm.importUris(uris, openElfDirectly = true)
    }
}

@Composable
private fun AppRoot(vm: MainViewModel) {
    val nav = rememberNavController()
    val snackbar = remember { SnackbarHostState() }
    val job by vm.job.collectAsStateWithLifecycle()
    val accepted by vm.settings.acceptedNotice.collectAsStateWithLifecycle()
    val update by vm.updates.state.collectAsStateWithLifecycle()

    // Check GitHub Releases for a newer build once the notice is accepted (rate-limited to every 6 h).
    LaunchedEffect(accepted) { if (accepted) vm.autoCheckUpdates() }

    LaunchedEffect(Unit) {
        vm.nav.collect { e ->
            when (e) {
                is NavEvent.OpenAnalysis -> nav.navigate("analysis/${e.id}")
                is NavEvent.OpenElf -> nav.navigate("elf/${e.id}/${e.libIndex}?tab=${e.tab}")
                NavEvent.OpenHistory -> nav.navigate("history") { launchSingleTop = true }
            }
        }
    }
    LaunchedEffect(Unit) { vm.messages.collect { snackbar.showSnackbar(it) } }

    NavHost(navController = nav, startDestination = "dashboard") {
        composable("dashboard") {
            DashboardScreen(vm, snackbar,
                onOpenHistory = { nav.navigate("history") },
                onOpenSettings = { nav.navigate("settings") },
                onOpenAnalysis = { nav.navigate("analysis/$it") })
        }
        composable("analysis/{id}", arguments = listOf(navArgument("id") { type = NavType.LongType })) { entry ->
            val id = entry.arguments?.getLong("id") ?: 0L
            ExtractionScreen(id, snackbar, onBack = { nav.popBackStack() }, onOpenElf = { idx -> nav.navigate("elf/$id/$idx?tab=0") })
        }
        composable(
            "elf/{id}/{idx}?tab={tab}",
            arguments = listOf(
                navArgument("id") { type = NavType.LongType },
                navArgument("idx") { type = NavType.IntType },
                navArgument("tab") { type = NavType.IntType; defaultValue = 0 },
            ),
        ) { entry ->
            val a = entry.arguments
            ElfAnalyzerScreen(a?.getLong("id") ?: 0L, a?.getInt("idx") ?: 0, a?.getInt("tab") ?: 0, snackbar, onBack = { nav.popBackStack() })
        }
        composable("history") {
            HistoryScreen(vm, snackbar, onBack = { nav.popBackStack() }, onOpen = { nav.navigate("analysis/$it") })
        }
        composable("settings") { SettingsScreen(vm, snackbar, onBack = { nav.popBackStack() }) }
    }

    (job as? JobState.Running)?.let { ProgressDialog(it, onCancel = vm::cancel) }
    if (accepted) UpdateDialog(update, vm)

    if (!accepted) AlertDialog(
        onDismissRequest = {},
        title = { Text("Authorized use only") },
        text = { Column(Modifier.verticalScroll(rememberScrollState())) { Text(LEGAL_NOTICE) } },
        confirmButton = { TextButton(onClick = { vm.settings.acceptNotice() }) { Text("I understand") } },
    )
}
