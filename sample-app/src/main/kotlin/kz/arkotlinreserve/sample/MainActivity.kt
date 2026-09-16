package kz.arkotlinreserve.sample

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.core.net.toUri
import kz.arkotlinreserve.ARKotlinReserve
import kz.arkotlinreserve.ARView
import kz.arkotlinreserve.AndroidARSession
import kz.arkotlinreserve.core.ARConfig
import kz.arkotlinreserve.core.AnalysisQuality
import kz.arkotlinreserve.core.TrackingState

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { SampleApplication() } }
    }
}

private enum class Screen { HOME, AR, SETTINGS }

@Composable
private fun SampleApplication() {
    var screen by remember { mutableStateOf(Screen.HOME) }
    var config by remember { mutableStateOf(ARConfig()) }
    when (screen) {
        Screen.HOME -> Home(onStart = { screen = Screen.AR }, onSettings = { screen = Screen.SETTINGS })
        Screen.SETTINGS -> SettingsScreen(config, { config = it }, { screen = Screen.HOME })
        Screen.AR -> ARScreen(config, onBack = { screen = Screen.HOME })
    }
}

@Composable
private fun Home(onStart: () -> Unit, onSettings: () -> Unit) {
    Scaffold { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(24.dp), verticalArrangement = Arrangement.Center) {
            Text("ARKotlinReserve", style = MaterialTheme.typography.headlineLarge)
            Spacer(Modifier.height(16.dp))
            Text(stringResource(R.string.tagline))
            Spacer(Modifier.height(24.dp))
            Text(stringResource(R.string.instructions))
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.print_hint))
            Spacer(Modifier.height(24.dp))
            Button(onClick = onStart, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.start_ar)) }
            TextButton(onClick = onSettings, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.settings)) }
        }
    }
}

@Composable
private fun SettingsScreen(config: ARConfig, onChange: (ARConfig) -> Unit, onBack: () -> Unit) {
    Scaffold { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(20.dp)) {
            Text(stringResource(R.string.settings), style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(16.dp))
            Text(stringResource(R.string.analysis_quality))
            AnalysisQuality.entries.forEach { quality ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = config.analysisQuality == quality, onClick = { onChange(config.copy(analysisQuality = quality)) })
                    Text(stringResource(when (quality) { AnalysisQuality.PERFORMANCE -> R.string.quality_fast; AnalysisQuality.BALANCED -> R.string.quality_balanced; AnalysisQuality.QUALITY -> R.string.quality_high }))
                }
            }
            Toggle(stringResource(R.string.smoothing), config.smoothingEnabled) { onChange(config.copy(smoothingEnabled = it)) }
            Toggle(stringResource(R.string.coordinate_axes), config.showCoordinateAxes) { onChange(config.copy(showCoordinateAxes = it)) }
            Spacer(Modifier.height(12.dp))
            Text(stringResource(R.string.calibration_hint))
            Spacer(Modifier.weight(1f))
            Button(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.back)) }
        }
    }
}

@Composable
private fun Toggle(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked, onCheckedChange = onChange)
        Text(label)
    }
}

@Composable
private fun ARScreen(config: ARConfig, onBack: () -> Unit) {
    val context = LocalContext.current
    val activity = LocalActivity.current ?: return
    var asked by remember { mutableStateOf(false) }
    var granted by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        asked = true
        granted = it
    }
    if (!granted) {
        val permanentlyDenied = asked && !ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.CAMERA)
        PermissionScreen(permanentlyDenied, { launcher.launch(Manifest.permission.CAMERA) }, {
            context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:${context.packageName}".toUri()))
        }, onBack)
        return
    }
    var session by remember { mutableStateOf<AndroidARSession?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        runCatching { ARKotlinReserve.create(context, config) }.onSuccess { session = it }.onFailure { error = it.message }
    }
    LaunchedEffect(config, session) { session?.updateConfig(config) }
    val latestSession by rememberUpdatedState(session)
    DisposableEffect(Unit) { onDispose { latestSession?.close() } }
    when {
        error != null -> MessageScreen(stringResource(R.string.startup_error, error.orEmpty()), onBack)
        session == null -> MessageScreen(stringResource(R.string.loading_ar), onBack)
        else -> ActiveAR(session!!, onBack)
    }
}

@Composable
private fun ActiveAR(session: AndroidARSession, onBack: () -> Unit) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val state by session.state.collectAsStateWithLifecycle()
    val diagnostics by session.diagnostics.collectAsStateWithLifecycle()
    val activeContent by session.activeContent.collectAsStateWithLifecycle()
    var showDiagnostics by remember { mutableStateOf(false) }
    var showInformation by remember { mutableStateOf(false) }
    var arView by remember { mutableStateOf<ARView?>(null) }
    DisposableEffect(arView) {
        arView?.let { session.attach(lifecycleOwner, it) }
        onDispose { session.detach() }
    }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context ->
                ARView(context).also { arView = it }
            },
        )
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(12.dp)) {
            Card { Column(Modifier.padding(12.dp)) {
                Text(statusText(state))
                activeContent?.let { Text(it.title, style = MaterialTheme.typography.titleMedium) }
                Text(stringResource(R.string.keep_marker_visible), style = MaterialTheme.typography.bodySmall)
            } }
            Spacer(Modifier.weight(1f))
            if (showDiagnostics) Card { Text(
                stringResource(R.string.diagnostics_format,
                    diagnostics.detectionMillis, diagnostics.processedFramesPerSecond, diagnostics.rendererFramesPerSecond,
                    diagnostics.analysisWidth, diagnostics.analysisHeight, diagnostics.activeMarkerId?.toString() ?: stringResource(R.string.not_available),
                    stringResource(if (diagnostics.approximateCalibration) R.string.calibration_approximate else R.string.calibration_profile),
                    diagnostics.reprojectionErrorPixels?.let { stringResource(R.string.reprojection_format, it) } ?: stringResource(R.string.not_available),
                ), Modifier.padding(12.dp)
            ) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Button(onClick = onBack) { Text(stringResource(R.string.back)) }
                if (activeContent != null) Button(onClick = { showInformation = true }) { Text(stringResource(R.string.information)) }
                Button(onClick = { showDiagnostics = !showDiagnostics }) { Text(stringResource(R.string.diagnostics)) }
            }
        }
        if (showInformation) AlertDialog(
            onDismissRequest = { showInformation = false },
            title = { Text(activeContent?.title.orEmpty()) },
            text = { Text(activeContent?.description.orEmpty()) },
            confirmButton = { TextButton(onClick = { showInformation = false }) { Text(stringResource(R.string.close)) } },
        )
    }
}

@Composable
private fun statusText(state: TrackingState): String = when (state) {
    TrackingState.Idle -> stringResource(R.string.status_ready)
    TrackingState.CameraStarting -> stringResource(R.string.status_camera_starting)
    TrackingState.Searching -> stringResource(R.string.status_searching)
    is TrackingState.Tracking -> stringResource(R.string.status_tracking, state.markerId)
    is TrackingState.TemporarilyLost -> stringResource(R.string.status_temporarily_lost)
    TrackingState.Lost -> stringResource(R.string.status_lost)
    is TrackingState.Error -> stringResource(R.string.status_error, state.message)
}

@Composable
private fun PermissionScreen(permanent: Boolean, request: () -> Unit, settings: () -> Unit, back: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center) {
        Text(stringResource(R.string.camera_permission), style = MaterialTheme.typography.headlineSmall)
        Text(stringResource(if (permanent) R.string.permission_blocked else R.string.permission_reason))
        Spacer(Modifier.height(16.dp))
        Button(onClick = if (permanent) settings else request, modifier = Modifier.fillMaxWidth()) { Text(stringResource(if (permanent) R.string.open_settings else R.string.grant_permission)) }
        TextButton(onClick = back, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.back)) }
    }
}

@Composable
private fun MessageScreen(message: String, back: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center) {
        Text(message)
        Spacer(Modifier.height(16.dp))
        Button(onClick = back) { Text(stringResource(R.string.back)) }
    }
}
