package com.progeo.luaflasher.ui.routers

import android.media.AudioManager
import android.media.ToneGenerator
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.progeo.core.ui.theme.ProGeoColors
import com.progeo.luaflasher.graph
import com.progeo.teltonika.api.SignalQuality
import kotlinx.coroutines.delay

/**
 * Full-screen signal meter for placing the antenna: the rating in large letters, the values, a
 * history of the last three minutes and a beep that gets faster as the signal gets better.
 * The screen stays on while it is open.
 */
@Composable
fun AntennaScreen(onBack: () -> Unit) {
    val graph = LocalContext.current.graph
    val s by graph.router.state.collectAsState()
    var sound by remember { mutableStateOf(false) }
    val view = LocalView.current

    DisposableEffect(Unit) {
        graph.router.antenna(true)
        view.keepScreenOn = true
        onDispose {
            graph.router.antenna(false)
            view.keepScreenOn = false
        }
    }

    val modem = s.modems.firstOrNull { it.signal.rsrp != null || it.signal.rssi != null } ?: s.modems.firstOrNull()
    val signal = modem?.signal
    val level by rememberUpdatedState(signal?.level ?: 0f)

    if (sound) {
        LaunchedEffect(Unit) {
            val tone = ToneGenerator(AudioManager.STREAM_MUSIC, 80)
            try {
                while (true) {
                    if (level > 0f) tone.startTone(ToneGenerator.TONE_PROP_BEEP, 60)
                    delay((150 + (1 - level) * 1100).toLong())
                }
            } finally {
                tone.release()
            }
        }
    }

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
            Text("Antenna mode", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            IconButton(onClick = { sound = !sound }) {
                Icon(
                    if (sound) Icons.AutoMirrored.Filled.VolumeUp else Icons.AutoMirrored.Filled.VolumeOff,
                    contentDescription = if (sound) "Sound off" else "Sound on",
                )
            }
        }
        val quality = signal?.quality ?: SignalQuality.NONE
        Text(
            quality.label,
            fontSize = 56.sp,
            fontWeight = FontWeight.Bold,
            color = qualityColor(quality, MaterialTheme.colorScheme.error),
            modifier = Modifier.align(Alignment.CenterHorizontally),
        )
        LinearProgressIndicator(progress = { signal?.level ?: 0f }, modifier = Modifier.fillMaxWidth().height(16.dp))
        Text(
            signal?.rsrp?.let { "RSRP $it dBm" } ?: signal?.rssi?.let { "RSSI $it dBm" } ?: "waiting for values…",
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.align(Alignment.CenterHorizontally),
        )
        Text(
            listOfNotNull(
                signal?.sinr?.let { "SINR %.0f dB".format(it) },
                signal?.rsrq?.let { "RSRQ %.0f dB".format(it) },
                signal?.rssi?.takeIf { signal.rsrp != null }?.let { "RSSI $it dBm" },
            ).joinToString(" · "),
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.align(Alignment.CenterHorizontally),
        )
        Text(
            listOfNotNull(modem?.operator, modem?.technology, modem?.band, modem?.cellId?.let { "cell $it" }).joinToString(" · "),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.align(Alignment.CenterHorizontally),
        )
        val best = s.signalHistory.mapNotNull { it.rsrp }.maxOrNull()
        best?.let {
            Text("Best so far: RSRP $it dBm", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.align(Alignment.CenterHorizontally))
        }
        History(s.signalHistory.map { it.level }, Modifier.fillMaxWidth().weight(1f))
        Text(
            "Move the antenna slowly and wait a few seconds at each spot. Good: RSRP above -90 dBm and SINR above 13 dB.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun History(levels: List<Float>, modifier: Modifier) {
    val line = ProGeoColors.Orange
    val grid = MaterialTheme.colorScheme.outlineVariant
    Canvas(modifier) {
        for (i in 1..3) {
            val y = size.height * i / 4f
            drawLine(grid, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
        }
        if (levels.size < 2) return@Canvas
        val step = size.width / (HISTORY_POINTS - 1)
        val offset = (HISTORY_POINTS - levels.size) * step
        val path = Path()
        levels.forEachIndexed { i, v ->
            val x = offset + i * step
            val y = size.height * (1f - v)
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, line, style = Stroke(width = 4f))
    }
}

private const val HISTORY_POINTS = 120
