package dev.ozcan.stress.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import dev.ozcan.stress.ui.theme.StressColors
import dev.ozcan.stress.ui.theme.StressMotion
import dev.ozcan.stress.ui.theme.StressType

/** A selectable [image] with a scrim-backed scene [name]. Selection uses the blue rule and label. */
@Composable
fun SceneCard(
    @DrawableRes image: Int,
    name: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val line by animateColorAsState(
        if (selected) StressColors.Cool else StressColors.Outline, StressMotion.state(), label = "scene outline",
    )
    val tint by animateColorAsState(
        if (selected) StressColors.Cool else StressColors.Text, StressMotion.state(), label = "scene name",
    )
    Box(
        modifier.heightIn(min = 48.dp).clip(PanelShape).background(StressColors.Surface)
            .border(1.dp, line, PanelShape)
            .selectable(selected, role = Role.RadioButton, onClick = onClick),
    ) {
        Image(
            painterResource(image), contentDescription = null, contentScale = ContentScale.Crop,
            modifier = Modifier.matchParentSize().padding(1.dp).clip(PanelShape),
        )
        Box(
            Modifier.matchParentSize().padding(1.dp).clip(PanelShape)
                .background(Brush.verticalGradient(0.30f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.95f))),
        )
        Text(name, Modifier.align(Alignment.BottomStart).padding(8.dp), style = StressType.Label, color = tint)
    }
}
