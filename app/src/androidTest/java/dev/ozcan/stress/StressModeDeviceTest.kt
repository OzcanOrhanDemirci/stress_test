package dev.ozcan.stress

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.ozcan.stress.engine.CpuKernel
import dev.ozcan.stress.engine.GpuBurner
import dev.ozcan.stress.engine.NativeBridge
import dev.ozcan.stress.engine.Workload
import dev.ozcan.stress.run.StressMode
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Each mode's recipe is a lab workload; it has to name kernels and burners
 * the phone really has, and a mode shows the GPU surface exactly when its
 * workload has a GPU part.
 */
@RunWith(AndroidJUnit4::class)
class StressModeDeviceTest {

    private val kernels = CpuKernel.parseTable(NativeBridge.kernelTable())
    private val burners = NativeBridge.gpuBurnerTable().mapIndexed(GpuBurner::parse)

    @Test
    fun everyRecipeIsARealWorkload() {
        for (mode in StressMode.entries) {
            val workload = Workload.parse(mode.recipe, kernels, burners, 8)
            assertEquals("${mode.name} recipe", mode.recipe, workload.describe())
            assertEquals("${mode.name} usesGpu", workload.gpu != null, mode.usesGpu)
        }
    }

    @Test
    fun onlyTheCinematicModesDrawAScene() {
        for (mode in StressMode.entries) {
            val gpu = Workload.parse(mode.recipe, kernels, burners, 8).gpu ?: continue
            assertEquals("${mode.name} scene", mode.scene != null, gpu.scene != false)
        }
    }
}
