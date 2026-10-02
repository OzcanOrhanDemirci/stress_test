package dev.ozcan.stress.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AcUnit
import androidx.compose.material.icons.rounded.Gavel
import androidx.compose.material.icons.rounded.GppBad
import androidx.compose.material.icons.rounded.LocalFireDepartment
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import dev.ozcan.stress.R
import dev.ozcan.stress.ui.components.PrimaryButton
import dev.ozcan.stress.ui.theme.StressColors

/**
 * The use-with-care notice shown when the app opens: the phone runs at full
 * capacity and gets very hot, device safety cannot guarantee protection, and
 * the responsibility is the user's. It is closed only by accepting it;
 * "don't show again" turns it off (Settings turns it back on).
 */
@Composable
fun SafetyNotice(safetyOn: Boolean, onAccept: (dontShowAgain: Boolean) -> Unit) {
    var dontShow by rememberSaveable { mutableStateOf(false) }
    var closing by remember { mutableStateOf(false) }
    val appear = remember { Animatable(0f) }
    LaunchedEffect(closing) {
        if (closing) {
            appear.animateTo(0f, tween(180))
            onAccept(dontShow)
        } else {
            appear.animateTo(1f, spring(dampingRatio = 0.75f, stiffness = 260f))
        }
    }
    val pulse by rememberInfiniteTransition(label = "notice").animateFloat(
        initialValue = 0.3f,
        targetValue = 0.75f,
        animationSpec = infiniteRepeatable(tween(1500), RepeatMode.Reverse),
        label = "pulse",
    )
    val color = StressColors.Warn
    val shape = RoundedCornerShape(28.dp)

    Dialog(
        onDismissRequest = {},
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false, usePlatformDefaultWidth = false),
    ) {
        // A deeper dim than a plain dialog's: the notice is the only thing to read.
        val window = (LocalView.current.parent as? DialogWindowProvider)?.window
        SideEffect { window?.setDimAmount(0.8f) }
        Column(
            Modifier
                .padding(horizontal = 18.dp, vertical = 24.dp)
                .widthIn(max = 440.dp)
                .graphicsLayer {
                    val t = appear.value.coerceIn(0f, 1.2f)
                    alpha = t.coerceAtMost(1f)
                    scaleX = 0.9f + 0.1f * t
                    scaleY = 0.9f + 0.1f * t
                    translationY = (1f - t) * 40.dp.toPx()
                }
                .clip(shape)
                .background(Brush.verticalGradient(listOf(Color(0xFF221A10), StressColors.SurfaceHigh, StressColors.Surface)), shape)
                .drawBehind {
                    drawRect(
                        Brush.radialGradient(
                            listOf(color.copy(alpha = 0.20f * pulse), Color.Transparent),
                            center = Offset(size.width * 0.18f, size.height * 0.06f),
                            radius = size.maxDimension * 0.8f,
                        ),
                    )
                }
                .border(1.5.dp, Brush.linearGradient(listOf(color.copy(alpha = 0.85f), color.copy(alpha = 0.15f))), shape)
                .verticalScroll(rememberScrollState())
                .padding(22.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Box(
                    Modifier
                        .size(56.dp)
                        .background(Brush.radialGradient(listOf(color.copy(alpha = 0.3f + 0.3f * pulse), color.copy(alpha = 0.06f))), CircleShape)
                        .border(1.dp, color.copy(alpha = 0.6f), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Rounded.WarningAmber, null, tint = color, modifier = Modifier.size(30.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.notice_title), style = MaterialTheme.typography.titleLarge, fontSize = 21.sp)
                    Text(stringResource(R.string.notice_subtitle), style = MaterialTheme.typography.labelLarge, color = color)
                }
            }
            Line(Icons.Rounded.LocalFireDepartment, StressColors.AccentHot, stringResource(R.string.notice_load))
            Line(Icons.Rounded.AcUnit, StressColors.Cool, stringResource(R.string.notice_care))
            Line(Icons.Rounded.Shield, StressColors.Good, stringResource(R.string.notice_protection))
            if (!safetyOn) {
                Line(Icons.Rounded.GppBad, StressColors.Bad, stringResource(R.string.notice_safety_off), StressColors.Bad)
            }
            Line(Icons.Rounded.Gavel, StressColors.TextDim, stringResource(R.string.notice_responsibility), StressColors.Text)

            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .clickable(role = Role.Checkbox) { dontShow = !dontShow }
                    .padding(end = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(
                    checked = dontShow,
                    onCheckedChange = { dontShow = it },
                    colors = CheckboxDefaults.colors(
                        checkedColor = StressColors.Accent,
                        uncheckedColor = StressColors.TextDim,
                        checkmarkColor = Color.White,
                    ),
                )
                Text(stringResource(R.string.notice_dont_show), style = MaterialTheme.typography.bodyMedium, color = StressColors.TextDim)
            }
            Spacer(Modifier.height(2.dp))
            PrimaryButton(
                text = stringResource(R.string.notice_accept).upper(),
                onClick = { closing = true },
                height = 56.dp,
            )
        }
    }
}

@Composable
private fun Line(icon: ImageVector, tint: Color, text: String, textColor: Color = StressColors.TextDim) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(
            Modifier
                .padding(top = 1.dp)
                .size(30.dp)
                .background(tint.copy(alpha = 0.14f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, null, tint = tint, modifier = Modifier.size(17.dp))
        }
        Text(text, style = MaterialTheme.typography.bodyMedium, color = textColor, modifier = Modifier.weight(1f))
    }
}
