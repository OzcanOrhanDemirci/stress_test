package dev.ozcan.stress.ui

import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.viewinterop.AndroidView
import dev.ozcan.stress.engine.GpuEngine

/**
 * The surface the renderer draws on. It hands its surface to [engine] when it
 * appears (then calls [onAttached]) and takes it back, synchronously, before
 * it goes away.
 *
 * With [framedScene] the scene's subject is drawn at [windowCenterY] (root
 * pixels, the centre of whatever part of the screen the panels leave
 * visible; null centres it) by moving the camera's principal point. The
 * surface keeps its full size and resolution, so the GPU draws the same
 * pixels either way. Without it, as in the lab, the scene is built exactly
 * as it always was.
 */
@Composable
fun GpuSurface(
    engine: GpuEngine,
    modifier: Modifier = Modifier,
    framedScene: Boolean = false,
    windowCenterY: Float? = null,
    onAttached: (() -> Unit)? = null,
) {
    var bounds by remember { mutableStateOf(Rect.Zero) }
    val offsetY = if (framedScene) sceneOffsetY(bounds, windowCenterY) else 0f
    AndroidView(
        factory = { context ->
            SurfaceView(context).apply {
                holder.addCallback(object : SurfaceHolder.Callback {
                    override fun surfaceCreated(holder: SurfaceHolder) {
                        engine.attachAsync(holder.surface, framedScene)
                        onAttached?.invoke()
                    }

                    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit

                    override fun surfaceDestroyed(holder: SurfaceHolder) = engine.detach()
                })
            }
        },
        modifier = if (framedScene) modifier.onGloballyPositioned { bounds = it.boundsInRoot() } else modifier,
        update = { if (framedScene) engine.setSceneOffsetY(offsetY) },
    )
}

/**
 * How far the subject moves from the surface's centre to [windowCenterY], in
 * surface heights, positive down as in Compose's layout. Centred while either
 * is unknown.
 */
internal fun sceneOffsetY(surface: Rect, windowCenterY: Float?): Float {
    if (windowCenterY == null || surface.height <= 0f) return 0f
    return (windowCenterY - surface.center.y) / surface.height
}
