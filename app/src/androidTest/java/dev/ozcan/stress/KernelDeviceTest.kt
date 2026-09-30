package dev.ozcan.stress

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.ozcan.stress.engine.CpuKernel
import dev.ozcan.stress.engine.KernelCatalog
import dev.ozcan.stress.engine.NativeBridge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The kernels on the real chip. A kernel's digest is only useful as an error
 * detector if it is exactly reproducible, on both core types.
 */
@RunWith(AndroidJUnit4::class)
class KernelDeviceTest {

    private val kernels = CpuKernel.parseTable(NativeBridge.kernelTable())

    private fun digest(kernel: CpuKernel, iterations: Long, cpu: Int): List<Long> {
        val d = NativeBridge.kernelDigest(kernel.index, iterations, cpu)
        checkNotNull(d) { "${kernel.code} failed to run" }
        if (cpu >= 0) assertEquals("${kernel.code} not pinned to cpu $cpu", 1L, d[2])
        return listOf(d[0], d[1])
    }

    @Test
    fun tableIsCompleteAndDescribed() {
        assertEquals(11, kernels.size)
        assertEquals(kernels.size, kernels.map { it.key }.toSet().size)
        assertEquals(KernelCatalog.keys, kernels.map { it.key }.toSet())
        kernels.forEach { assertTrue("${it.code} does no work", it.opsPerIteration > 0) }
    }

    @Test
    fun digestsRepeatAcrossRunsAndCoreTypes() {
        for (k in kernels) {
            val onLittle = digest(k, 3, cpu = 0)
            val again = digest(k, 3, cpu = 0)
            val onBig = digest(k, 3, cpu = 7)
            assertEquals("${k.code} not repeatable", onLittle, again)
            assertEquals("${k.code} differs between A510 and A715", onLittle, onBig)
            assertNotEquals("${k.code} digest is empty", listOf(0L, 0L), digest(k, 1, cpu = 7))
        }
    }

    @Test
    fun digestsDependOnTheWorkDone() {
        for (k in kernels) {
            assertNotEquals("${k.code} ignores the iteration count", digest(k, 1, cpu = 7), digest(k, 2, cpu = 7))
        }
    }
}
