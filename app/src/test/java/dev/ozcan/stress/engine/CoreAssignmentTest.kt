package dev.ozcan.stress.engine

import dev.ozcan.stress.TestSamples
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CoreAssignmentTest {

    private val kernels = TestSamples.kernels

    private fun parse(text: String, cpus: Int = 8) = CoreAssignment.parse(text, kernels, cpus)

    @Test
    fun `a single key runs on every cpu`() {
        val a = parse("fp32_gemm")
        assertTrue(a.kernelPerCpu.all { it == TestSamples.gemm })
        assertEquals("fp32_gemm", a.describe())
    }

    @Test
    fun `groups assign ranges and leave the rest idle`() {
        val a = parse("0-3:dry, 7:fp32_gemm")
        assertEquals(List(4) { TestSamples.dry } + listOf(null, null, null, TestSamples.gemm), a.kernelPerCpu)
        assertEquals(intArrayOf(0, 0, 0, 0, -1, -1, -1, 2).toList(), a.toNative().toList())
    }

    @Test
    fun `describe round-trips through parse`() {
        for (text in listOf("0-3:dry,4-7:fp32_gemm", "0:dry,2-3:fp32_gemm,7:dry", "fp32_gemm")) {
            val a = parse(text)
            assertEquals(text, a.describe())
            assertEquals(a, parse(a.describe()))
        }
    }

    @Test
    fun `bad input is rejected with a reason`() {
        assertThrows(IllegalArgumentException::class.java) { parse("nope") }
        assertThrows(IllegalArgumentException::class.java) { parse("0-8:dry") }
        assertThrows(IllegalArgumentException::class.java) { parse("3-1:dry") }
        assertThrows(IllegalArgumentException::class.java) { parse("0-3:dry,3:fp32_gemm") }
        assertThrows(IllegalArgumentException::class.java) { parse("") }
        assertThrows(IllegalArgumentException::class.java) { parse("0:dry:x") }
    }

    @Test
    fun `the cpu count is the device's`() {
        assertEquals(6, parse("fp32_gemm", cpus = 6).cpuCount)
        assertThrows(IllegalArgumentException::class.java) { parse("0-7:dry", cpus = 6) }
        val big = parse("0-3:dry,4-11:fp32_gemm", cpus = 12)
        assertEquals(12, big.toNative().size)
        assertEquals("0-3:dry,4-11:fp32_gemm", big.describe())
        assertThrows(IllegalArgumentException::class.java) { CoreAssignment(emptyList()) }
    }

    @Test
    fun `an all idle assignment is idle`() {
        assertTrue(CoreAssignment.uniform(null, 8).isIdle)
        assertNull(CoreAssignment.uniform(null, 8).kernelPerCpu.first())
    }
}
