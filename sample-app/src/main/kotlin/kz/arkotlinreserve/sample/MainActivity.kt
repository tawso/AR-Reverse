package kz.arkotlinreserve.sample

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kz.arkotlinreserve.ARKotlinReserve
import kz.arkotlinreserve.AndroidARSession
import kz.arkotlinreserve.core.ARConfig
import kz.arkotlinreserve.core.AnalysisQuality
import kz.arkotlinreserve.core.TrackingState
import kz.arkotlinreserve.renderer.ARRenderView

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
            Text("Маркерге негізделген офлайн AR құралдар жинағы")
            Spacer(Modifier.height(24.dp))
            Text("Маркерді ақ жиегімен 100% масштабта басып шығарыңыз. Қара шаршының өлшемін сызғышпен тексеріңіз.")
            Spacer(Modifier.height(24.dp))
            Button(onClick = onStart, modifier = Modifier.fillMaxWidth()) { Text("AR бастау") }
            TextButton(onClick = onSettings, modifier = Modifier.fillMaxWidth()) { Text("Баптаулар") }
        }
    }
}

@Composable
private fun SettingsScreen(config: ARConfig, onChange: (ARConfig) -> Unit, onBack: () -> Unit) {
    Scaffold { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(20.dp)) {
            Text("Баптаулар", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(16.dp))
            Text("Талдау сапасы")
            AnalysisQuality.entries.forEach { quality ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = config.analysisQuality == quality, onClick = { onChange(config.copy(analysisQuality = quality)) })
                    Text(when (quality) { AnalysisQuality.PERFORMANCE -> "Жылдам"; AnalysisQuality.BALANCED -> "Теңгерімді"; AnalysisQuality.QUALITY -> "Жоғары" })
                }
            }
            Toggle("Қозғалысты тегістеу", config.smoothingEnabled) { onChange(config.copy(smoothingEnabled = it)) }
            Toggle("Координата осьтері", config.showCoordinateAxes) { onChange(config.copy(showCoordinateAxes = it)) }
            Spacer(Modifier.height(12.dp))
            Text("Калибрлеу: профиль табылмаса, жуық режим қолданылады. Ол нақты өлшеуге арналмаған.")
            Spacer(Modifier.weight(1f))
            Button(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("Артқа") }
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
    val activity = context as ComponentActivity
    var asked by remember { mutableStateOf(false) }
    var granted by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        asked = true
        granted = it
    }
    if (!granted) {
        val permanentlyDenied = asked && !ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.CAMERA)
        PermissionScreen(permanentlyDenied, { launcher.launch(Manifest.permission.CAMERA) }, {
            context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
        }, onBack)
        return
    }
    var session by remember { mutableStateOf<AndroidARSession?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        runCatching { ARKotlinReserve.create(context, config) }.onSuccess { session = it }.onFailure { error = it.message }
    }
    LaunchedEffect(config, session) { session?.updateConfig(config) }
    DisposableEffect(session) { onDispose { session?.close() } }
    when {
        error != null -> MessageScreen("Іске қосу қатесі: $error", onBack)
        session == null -> MessageScreen("AR жүктелуде…", onBack)
        else -> ActiveAR(session!!, onBack)
    }
}

@Composable
private fun ActiveAR(session: AndroidARSession, onBack: () -> Unit) {
    val activity = LocalContext.current as ComponentActivity
    val state by session.state.collectAsStateWithLifecycle()
    val diagnostics by session.diagnostics.collectAsStateWithLifecycle()
    var showDiagnostics by remember { mutableStateOf(false) }
    var views by remember { mutableStateOf<Pair<PreviewView, ARRenderView>?>(null) }
    DisposableEffect(views) {
        views?.let { session.attach(activity, it.first, it.second) }
        onDispose { session.detach() }
    }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context ->
                val preview = PreviewView(context)
                val renderer = ARRenderView(context)
                FrameLayout(context).apply {
                    addView(preview, FrameLayout.LayoutParams(-1, -1))
                    addView(renderer, FrameLayout.LayoutParams(-1, -1))
                    views = preview to renderer
                }
            },
        )
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(12.dp)) {
            Card { Column(Modifier.padding(12.dp)) {
                Text(statusText(state))
                Text("Маркерді толық кадрда ұстаңыз", style = MaterialTheme.typography.bodySmall)
            } }
            Spacer(Modifier.weight(1f))
            if (showDiagnostics) Card { Text(
                "Детекция: %.1f мс\nТалдау: %.1f кадр/с\nРендерер: %.1f кадр/с\nӨлшем: %d×%d\nМаркер: %s\nКалибрлеу: %s\nРепроекция: %s".format(
                    diagnostics.detectionMillis, diagnostics.processedFramesPerSecond, diagnostics.rendererFramesPerSecond,
                    diagnostics.analysisWidth, diagnostics.analysisHeight, diagnostics.activeMarkerId ?: "—",
                    if (diagnostics.approximateCalibration) "жуық" else "профиль",
                    diagnostics.reprojectionErrorPixels?.let { "%.2f px".format(it) } ?: "—",
                ), Modifier.padding(12.dp)
            ) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Button(onClick = onBack) { Text("Артқа") }
                Button(onClick = { showDiagnostics = !showDiagnostics }) { Text("Диагностика") }
            }
        }
    }
}

private fun statusText(state: TrackingState): String = when (state) {
    TrackingState.Idle -> "Дайын"
    TrackingState.CameraStarting -> "Камера іске қосылуда…"
    TrackingState.Searching -> "Маркер ізделуде"
    is TrackingState.Tracking -> "Маркер ${state.markerId} бақылануда"
    is TrackingState.TemporarilyLost -> "Маркер уақытша көрінбейді"
    TrackingState.Lost -> "Маркер жоғалды"
    is TrackingState.Error -> "Қате: ${state.message}"
}

@Composable
private fun PermissionScreen(permanent: Boolean, request: () -> Unit, settings: () -> Unit, back: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center) {
        Text("Камераға рұқсат қажет", style = MaterialTheme.typography.headlineSmall)
        Text(if (permanent) "Рұқсат бұғатталған. Оны қолданба баптауларында қосыңыз." else "AR маркерлерін тану үшін камера қолданылады. Кадрлар құрылғыдан жіберілмейді.")
        Spacer(Modifier.height(16.dp))
        Button(onClick = if (permanent) settings else request, modifier = Modifier.fillMaxWidth()) { Text(if (permanent) "Баптауларды ашу" else "Рұқсат беру") }
        TextButton(onClick = back, modifier = Modifier.fillMaxWidth()) { Text("Артқа") }
    }
}

@Composable
private fun MessageScreen(message: String, back: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center) {
        Text(message)
        Spacer(Modifier.height(16.dp))
        Button(onClick = back) { Text("Артқа") }
    }
}
