package dev.ozcan.stress.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class CpuKernelTest {

    @Test
    fun `parses a native table line`() {
        val k = CpuKernel.parse(3, "fp64_gemm|C3|FLOP|13824|16128")
        assertEquals(CpuKernel(3, "fp64_gemm", "C3", WorkUnit.Flop, 13824.0, 16128), k)
    }

    @Test
    fun `rejects malformed lines and unknown units`() {
        assertThrows(IllegalArgumentException::class.java) { CpuKernel.parse(0, "a|b|FLOP|1") }
        assertThrows(IllegalStateException::class.java) { CpuKernel.parse(0, "a|b|WATT|1|2") }
    }

    @Test
    fun `work counts batches times iterations times ops`() {
        val k = CpuKernel(0, "k", "K", WorkUnit.Op, 4.0, 64)
        val w = WorkerState(cpu = 0, kernel = k, iterationsPerBatch = 10, batches = 3, errors = 0, lastCpu = 0, flags = 0)
        assertEquals(120.0, w.work, 0.0)
    }
}
