package dev.ozcan.stress.ui

import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import dev.ozcan.stress.engine.GpuEngine

/**
 * The surface the renderer draws on. It hands its surface to [engine] when it
 * appears and takes it back, synchronously, before it goes away.
 */
@Composable
fun GpuSurface(engine: GpuEngine, modifier: Modifier = Modifier) {
    AndroidView(
        factory = { context ->
            SurfaceView(context).apply {
                holder.addCallback(object : SurfaceHolder.Callback {
                    override fun surfaceCreated(holder: SurfaceHolder) = engine.attachAsync(holder.surface)

                    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit

                    override fun surfaceDestroyed(holder: SurfaceHolder) = engine.detach()
                })
            }
        },
        modifier = modifier,
    )
}
