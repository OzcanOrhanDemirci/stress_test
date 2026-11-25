package dev.ozcan.stress.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GpuSnapshotTest {
    private fun snapshot(quality: SceneQuality, scale: Long, settled: Boolean): GpuSnapshot {
        val fields = LongArray(GpuEngine.SNAPSHOT_STRIDE)
        fields[0] = GpuState.Running.code.toLong()
        fields[14] = quality.code.toLong()
        fields[15] = scale
        fields[16] = if (settled) quality.code.toLong() else -1
        fields[17] = if (settled) scale else 0
        fields[18] = if (settled) 1 else 0
        return GpuSnapshot.decode(fields, null)
    }

    @Test
    fun `startup frames are visible in the snapshot but do not lower the run quality`() {
        val lowProbe = snapshot(SceneQuality.Low, 1862, settled = false)
        val mediumProbe = snapshot(SceneQuality.Medium, 4500, settled = false)
        val high = snapshot(SceneQuality.High, 6500, settled = true)

        assertEquals(SceneDraw(SceneQuality.Low, 1862), lowProbe.sceneDraw)
        assertNull(lowProbe.lowestSceneDraw)
        val probing = GpuRunStatus().include(lowProbe).include(mediumProbe)
        assertNull(probing.lowestSceneDraw)
        assertEquals(SceneDraw(SceneQuality.High, 6500), probing.include(high).lowestSceneDraw)
    }

    @Test
    fun `a refused step records its fallback and later renderers cannot erase the minimum`() {
        val medium = snapshot(SceneQuality.Medium, 4500, settled = true)
        val smaller = snapshot(SceneQuality.Medium, 4000, settled = true)
        val high = snapshot(SceneQuality.High, 6500, settled = true)
        val status = GpuRunStatus().include(medium)

        assertEquals(medium.sceneDraw, status.lowestSceneDraw)
        assertEquals(smaller.sceneDraw, status.include(smaller).include(high).lowestSceneDraw)
    }

    @Test
    fun `a new probe keeps the previous minimum but exposes no settled current draw`() {
        val high = snapshot(SceneQuality.High, 6500, settled = true)
        val resize = snapshot(SceneQuality.Low, 1862, settled = false).copy(lowestSceneDraw = high.lowestSceneDraw)

        assertEquals(high.sceneDraw, high.settledSceneDraw)
        assertNull(resize.settledSceneDraw)
        assertEquals(high.sceneDraw, GpuRunStatus().include(resize).lowestSceneDraw)
    }
}
