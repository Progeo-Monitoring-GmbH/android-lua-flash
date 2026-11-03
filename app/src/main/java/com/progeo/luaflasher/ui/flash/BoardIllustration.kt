package com.progeo.luaflasher.ui.flash

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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics

/**
 * The board with the boot jumper (GPIO0 to GND) and the RST button, so the user sees what
 * to do (PLAN.md section 4, step 6). [jumperSet] draws the jumper cap; RST pulses.
 */
@Composable
fun BoardIllustration(jumperSet: Boolean, modifier: Modifier = Modifier) {
    val pulse by rememberInfiniteTransition(label = "rst").animateFloat(
        initialValue = 0.2f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
        label = "rst-alpha",
    )
    val accent = MaterialTheme.colorScheme.primary
    val warn = MaterialTheme.colorScheme.error
    val description = if (jumperSet) "Board with the boot jumper set; press RST" else "Board with the boot jumper removed; press RST"

    Canvas(
        modifier
            .fillMaxWidth()
            .aspectRatio(1.6f)
            .semantics { contentDescription = description },
    ) {
        val w = size.width
        val h = size.height
        // PCB
        drawRoundRect(Color(0xFF1B5E20), Offset(w * 0.05f, h * 0.08f), Size(w * 0.9f, h * 0.84f), CornerRadius(w * 0.03f))
        // ESP module with antenna
        drawRect(Color(0xFF424242), Offset(w * 0.12f, h * 0.18f), Size(w * 0.36f, h * 0.5f))
        drawRect(Color(0xFFBDBDBD), Offset(w * 0.15f, h * 0.24f), Size(w * 0.3f, h * 0.3f))
        // USB-UART chip
        drawRect(Color(0xFF212121), Offset(w * 0.6f, h * 0.6f), Size(w * 0.12f, h * 0.14f))

        // Jumper header: two pins
        val pinY = h * 0.3f
        val pin1 = Offset(w * 0.64f, pinY)
        val pin2 = Offset(w * 0.74f, pinY)
        val pinSize = w * 0.035f
        listOf(pin1, pin2).forEach {
            drawRect(Color(0xFFFFD54F), Offset(it.x - pinSize / 2, it.y - pinSize / 2), Size(pinSize, pinSize))
        }
        if (jumperSet) {
            drawRoundRect(
                Color(0xFF1565C0),
                Offset(pin1.x - pinSize, pinY - pinSize * 1.1f),
                Size(pin2.x - pin1.x + pinSize * 2, pinSize * 2.2f),
                CornerRadius(pinSize / 2),
            )
        }
        drawRoundRect(
            if (jumperSet) accent else warn,
            Offset(pin1.x - pinSize * 1.8f, pinY - pinSize * 2f),
            Size(pin2.x - pin1.x + pinSize * 3.6f, pinSize * 4f),
            CornerRadius(pinSize),
            style = Stroke(width = w * 0.008f),
        )

        // RST button, pulsing
        val rst = Offset(w * 0.82f, h * 0.72f)
        drawCircle(Color(0xFF9E9E9E), radius = w * 0.04f, center = rst)
        drawCircle(Color(0xFF616161), radius = w * 0.025f, center = rst)
        drawCircle(accent.copy(alpha = pulse), radius = w * 0.065f, center = rst, style = Stroke(width = w * 0.01f))

        drawContext.canvas.nativeCanvas.apply {
            val paint = android.graphics.Paint().apply {
                color = android.graphics.Color.WHITE
                textSize = h * 0.07f
                isAntiAlias = true
            }
            drawText(if (jumperSet) "GPIO0 jumper SET" else "jumper REMOVED", w * 0.56f, h * 0.18f, paint)
            drawText("RST", rst.x - w * 0.04f, rst.y - w * 0.08f, paint)
        }
    }
}
