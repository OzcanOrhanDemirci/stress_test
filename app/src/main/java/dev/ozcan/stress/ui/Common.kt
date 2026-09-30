package dev.ozcan.stress.ui

import android.view.WindowManager
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.ozcan.stress.ui.theme.StressColors
import java.util.Locale

/** A titled card. */
@Composable
fun Panel(title: String, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(StressColors.Surface, RoundedCornerShape(14.dp))
            .border(1.dp, StressColors.Outline, RoundedCornerShape(14.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(title.uppercase(), style = MaterialTheme.typography.labelMedium, color = StressColors.TextDim, letterSpacing = 1.5.sp)
        content()
    }
}

/** A label on the left, a monospaced value on the right. */
@Composable
fun Field(label: String, value: String, valueColor: Color = StressColors.Text) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = StressColors.TextDim, style = MaterialTheme.typography.bodyMedium)
        Text(value, color = valueColor, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace)
    }
}

/** A headline number with its label, for summary grids. */
@Composable
fun Stat(label: String, value: String, modifier: Modifier = Modifier, accent: Color = StressColors.Text) {
    Column(
        modifier = modifier
            .background(StressColors.SurfaceHigh, RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = StressColors.TextDim)
        Text(value, style = MaterialTheme.typography.titleLarge, color = accent, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold)
    }
}

/** The radiation trefoil: a centre disc and three blades. */
@Composable
fun Trefoil(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val r = size.minDimension / 2
        val c = Offset(size.width / 2, size.height / 2)
        drawCircle(color, radius = r * 0.18f, center = c)
        for (i in 0 until 3) {
            val start = -90f - 30f + i * 120f
            drawArc(
                color = color,
                startAngle = start,
                sweepAngle = 60f,
                useCenter = true,
                topLeft = Offset(c.x - r, c.y - r),
                size = Size(r * 2, r * 2),
            )
        }
        // Clear the ring between the disc and the blades.
        drawCircle(StressColors.Background, radius = r * 0.3f, center = c)
        drawCircle(color, radius = r * 0.18f, center = c)
    }
}

/**
 * While shown, drives the display as hard as it goes: full brightness and the
 * highest refresh rate. Both add to the power the run measures, the same way
 * every time. Restores the previous settings when it leaves.
 */
@Composable
fun MaxDisplayEffect() {
    val activity = LocalActivity.current ?: return
    DisposableEffect(activity) {
        val window = activity.window
        val previous = WindowManager.LayoutParams().apply { copyFrom(window.attributes) }
        val current = activity.display.mode
        val fastest = activity.display.supportedModes
            .filter { it.physicalWidth == current.physicalWidth && it.physicalHeight == current.physicalHeight }
            .maxByOrNull { it.refreshRate }
        window.attributes = window.attributes.apply {
            screenBrightness = 1f
            if (fastest != null) preferredDisplayModeId = fastest.modeId
        }
        onDispose {
            window.attributes = window.attributes.apply {
                screenBrightness = previous.screenBrightness
                preferredDisplayModeId = previous.preferredDisplayModeId
            }
        }
    }
}

object Durations {
    /** 125 s -> "2:05"; an hour or more -> "1:02:05". */
    fun clock(seconds: Double): String {
        val total = seconds.toLong().coerceAtLeast(0)
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) "%d:%02d:%02d".format(Locale.ROOT, h, m, s) else "%d:%02d".format(Locale.ROOT, m, s)
    }

    /** 2.52 h -> "2 sa 31 dk". */
    fun hours(hours: Double): String {
        val minutes = (hours * 60).toLong()
        return if (minutes >= 60) "${minutes / 60} sa ${minutes % 60} dk" else "$minutes dk"
    }
}
