package com.progeo.luaflasher.ui.flash

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.DrawStyle
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import com.progeo.core.ui.theme.ProGeoColors

// Colors of the real controller (see the photo in the wizard design).
private val Pcb = Color(0xFF2E7D3A)
private val PcbEdge = Color(0xFF1F5E2A)
private val Gold = Color(0xFFD4A837)
private val Black = Color(0xFF1C1C1C)
private val Black2 = Color(0xFF151515)
private val Metal = Color(0xFFBDBDBD)
private val MetalDark = Color(0xFF8A8A8A)
private val Pad = Color(0xFFC9C9C9)
private val OledFrame = Color(0xFFDADADA)
private val OledScreen = Color(0xFF0B0B12)
private val Ribbon = Color(0xFFC98A1B)
private val PinHeaderGreen = Color(0xFF8FCB7A)
private val Terminal = Color(0xFF3FA65A)
private val Adapter = Color(0xFF1F5FAE)
private val JumperCap = Color(0xFFFBBC15)
private val JumperEdge = Color(0xFF9A6B00)
private val ButtonBody = Color(0xFF3A3A3A)
private val Plunger = Color(0xFFEDEDED)

/** Darker than the brand orange so the small state words stay readable on the light panels. */
private val ActionText = Color(0xFFC2410C)

/**
 * The controller seen from the top, as on the bench: boot jumper at the top-left corner, the
 * USB-UART adapter on the left edge, RST as the side button below the display. Labels on the
 * left point to these three parts. [jumperSet]: the cap drops onto the pins (bootloader mode)
 * or lifts off (normal mode); RST pulses and is pushed in both.
 *
 * Drawn in a 320 × 200 grid that is scaled to the width.
 */
@Composable
fun BoardIllustration(jumperSet: Boolean, modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "board")
    val pulse by transition.animateFloat(
        initialValue = 0.2f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(700, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "rst-ring",
    )
    val press by transition.animateFloat(
        initialValue = -2f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(700, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "rst-press",
    )
    val jumper by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(2800, easing = LinearEasing), RepeatMode.Restart),
        label = "jumper",
    )
    val measurer = rememberTextMeasurer()
    val label = MaterialTheme.colorScheme.primary
    val font = MaterialTheme.typography.labelLarge.fontFamily
    val description = if (jumperSet) "Controller with the boot jumper set; press RST" else "Controller with the boot jumper removed; press RST"

    Canvas(
        modifier
            .fillMaxWidth()
            .aspectRatio(1.6f)
            .semantics { contentDescription = description },
    ) {
        val g = Grid(this, size.width / 320f)
        g.board()
        g.adapter()
        g.jumper(jumperSet, jumper)
        g.resetButton(pulse, press)
        g.labels(measurer, font, label, jumperSet)
    }
}

/** Draws in the 320 × 200 design grid. */
private class Grid(val scope: DrawScope, val s: Float) {
    fun o(x: Float, y: Float) = Offset(x * s, y * s)

    fun rect(x: Float, y: Float, w: Float, h: Float, color: Color, r: Float = 0f, style: DrawStyle = Fill, alpha: Float = 1f) =
        scope.drawRoundRect(color, o(x, y), Size(w * s, h * s), CornerRadius(r * s), alpha = alpha, style = style)

    fun circle(x: Float, y: Float, r: Float, color: Color, style: DrawStyle = Fill, alpha: Float = 1f) =
        scope.drawCircle(color, r * s, o(x, y), alpha = alpha, style = style)

    fun stroke(width: Float, dash: FloatArray? = null) =
        Stroke(width * s, cap = StrokeCap.Round, pathEffect = dash?.let { PathEffect.dashPathEffect(FloatArray(it.size) { i -> it[i] * s }) })

    fun board() {
        // USB cable leaving the adapter to the top left
        val cable = Path().apply {
            moveTo(76 * s, 49 * s)
            cubicTo(64 * s, 49 * s, 60 * s, 28 * s, 62 * s, 0f)
        }
        scope.drawPath(cable, Black, style = stroke(7f))

        rect(120f, 8f, 184f, 186f, Pcb, r = 4f)
        rect(120f, 8f, 184f, 186f, PcbEdge, r = 4f, style = stroke(1.5f))

        // the long double pin header along the top
        rect(146f, 10f, 104f, 13f, Black, r = 1.5f)
        for (row in 0..1) for (i in 0..16) {
            val x = 150f + i * 6f
            val y = 13.5f + row * 6f
            circle(x, y, 2f, Gold)
            circle(x, y, 0.9f, Black)
        }

        // standoffs and the second push button (top right)
        circle(276f, 15f, 5f, Metal)
        circle(276f, 15f, 5f, MetalDark, style = stroke(1f))
        circle(130f, 150f, 5f, Metal)
        circle(130f, 150f, 5f, MetalDark, style = stroke(1f))
        rect(286f, 13f, 13f, 13f, Color(0xFFE0E0E0), r = 1.5f)
        circle(292.5f, 19.5f, 3.6f, Black)

        // OLED display: the landmark
        rect(122f, 25f, 68f, 68f, Black, r = 3f)
        rect(122f, 25f, 68f, 68f, OledFrame, r = 3f, style = stroke(1f))
        rect(124.5f, 33f, 63f, 47f, OledScreen)
        rect(147f, 90f, 20f, 6f, Ribbon)

        // headers, chips and modules
        rect(192f, 31f, 5f, 34f, Black, r = 1f)
        rect(204f, 34f, 22f, 6f, PinHeaderGreen, r = 1f)
        rect(207f, 45f, 22f, 13f, Black, r = 1f)
        rect(244f, 36f, 6f, 36f, Black, r = 1f)
        rect(187f, 96f, 21f, 24f, Black, r = 1f)
        rect(258f, 88f, 44f, 11f, Black, r = 2f)
        rect(262f, 120f, 40f, 30f, Black2, r = 2f)
        rect(138f, 128f, 40f, 4f, Pad)
        rect(138f, 142f, 40f, 4f, Pad)
        rect(216f, 102f, 4f, 22f, Pad)
        rect(226f, 102f, 4f, 22f, Pad)

        // perfboard and the two rows of holes at the bottom
        for (j in 0..8) for (i in 0..7) hole(252f + i * 6.3f, 37f + j * 6f)
        for (row in 0..1) for (i in 0..19) hole(129f + i * 6.3f, 169f + row * 13f)

        // screw terminals
        rect(264f, 158f, 38f, 34f, Terminal, r = 2f)
        circle(276f, 170f, 3.5f, Color(0xFFE0E0E0))
        circle(290f, 170f, 3.5f, Color(0xFFE0E0E0))
    }

    private fun hole(x: Float, y: Float) {
        circle(x, y, 2.1f, Gold)
        circle(x, y, 1f, Pcb)
    }

    fun adapter() {
        rect(114f, 31f, 7f, 38f, Black, r = 1f) // 6-pin header on the board's edge
        rect(84f, 26f, 31f, 47f, Adapter, r = 2f)
        rect(74f, 41f, 12f, 16f, Pad, r = 1.5f) // USB plug
        rect(74f, 41f, 12f, 16f, Color(0xFF9A9A9A), r = 1.5f, style = stroke(0.8f))
    }

    /** [t] runs 0..1 every 2.8 s: the cap drops on (set) or lifts off (removed). */
    fun jumper(set: Boolean, t: Float) {
        rect(121f, 10f, 11f, 16f, Black, r = 1.5f)
        circle(126.5f, 14f, 1.6f, Gold)
        circle(126.5f, 22f, 1.6f, Gold)
        val dx: Float
        val dy: Float
        val alpha: Float
        if (set) {
            val e = FastOutSlowInEasing.transform((t / 0.35f).coerceAtMost(1f))
            dx = 0f
            dy = -9f * (1 - e)
            alpha = e
        } else {
            // where the cap was
            rect(120f, 9f, 13f, 18f, JumperCap, r = 3f, style = stroke(1f, floatArrayOf(2f, 1.5f)))
            val e = FastOutSlowInEasing.transform(((t - 0.25f) / 0.45f).coerceIn(0f, 1f))
            dx = 9f * e
            dy = -9f * e
            alpha = 1 - e
        }
        rect(120f + dx, 9f + dy, 13f, 18f, JumperCap, r = 3f, alpha = alpha)
        rect(120f + dx, 9f + dy, 13f, 18f, JumperEdge, r = 3f, style = stroke(0.8f), alpha = alpha)
        rect(114.5f, 4f, 24f, 28f, ProGeoColors.Orange, r = 6f, style = stroke(2f))
    }

    fun resetButton(pulse: Float, press: Float) {
        rect(112f, 97f, 14f, 17f, ButtonBody, r = 1.5f)
        rect(107f + press, 102f, 6f, 7f, Plunger, r = 1.5f)
        circle(113f, 105.5f, 13f, ProGeoColors.Orange, style = stroke(2.5f), alpha = pulse)
    }

    fun labels(measurer: TextMeasurer, font: FontFamily?, color: Color, jumperSet: Boolean) {
        val dash = floatArrayOf(2f, 2f)
        listOf(15f to 113f, 49f to 73f, 105.5f to 99f).forEach { (y, to) ->
            scope.drawLine(color, o(55f, y), o(to, y), strokeWidth = 0.8f * s, pathEffect = stroke(0.8f, dash).pathEffect)
        }
        text(measurer, font, "Jumper", 15f, 9f, color)
        text(measurer, font, if (jumperSet) "ON" else "OFF", 24f, 7f, ActionText)
        text(measurer, font, "USB", 52f, 9f, color)
        text(measurer, font, "RST", 105f, 9f, color)
        text(measurer, font, "press", 114f, 7f, ActionText)
    }

    /** Right-aligned at x = 52, [baseline] and [size] in grid units. */
    private fun text(measurer: TextMeasurer, font: FontFamily?, text: String, baseline: Float, size: Float, color: Color) {
        val style = TextStyle(color = color, fontSize = with(scope) { (size * s).toSp() }, fontWeight = FontWeight.Bold, fontFamily = font)
        val layout = measurer.measure(text, style)
        scope.drawText(layout, topLeft = Offset(52f * s - layout.size.width, baseline * s - layout.firstBaseline))
    }
}
