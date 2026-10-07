package com.manga.translate

import com.manga.translate.platform.DeviceResourcePolicy
import com.manga.translate.platform.DeviceResourceSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceResourcePolicyTest {
    @Test
    fun `system and heap availability use the tighter remaining value`() {
        val snapshot = snapshot(systemMib = 600, heapMib = 200, cpuCores = 8)

        assertEquals(600L * MIB, snapshot.displayAvailableBytes)
        assertEquals(200L * MIB, snapshot.effectiveAvailableBytes)
        assertTrue(DeviceResourcePolicy.assess(snapshot, 150L * MIB).shouldWarn)
    }

    @Test
    fun `heap availability is a fallback when Android memory info is unavailable`() {
        val snapshot = DeviceResourceSnapshot(
            systemAvailableBytes = null,
            appHeapHeadroomBytes = 400L * MIB,
            systemReportsLowMemory = false,
            cpuCoreCount = 8
        )

        assertEquals(400L * MIB, snapshot.displayAvailableBytes)
        assertFalse(DeviceResourcePolicy.assess(snapshot, 100L * MIB).shouldWarn)
    }

    @Test
    fun `worker CPU budget reserves capacity for Android`() {
        assertEquals(1, DeviceResourcePolicy.usableWorkerCpuCores(2))
        assertEquals(3, DeviceResourcePolicy.usableWorkerCpuCores(4))
        assertEquals(6, DeviceResourcePolicy.usableWorkerCpuCores(8))
    }

    @Test
    fun `concurrency recommendation is limited by both CPU and remaining memory`() {
        val cpuLimited = DeviceResourcePolicy.recommendConcurrency(
            snapshot = snapshot(systemMib = 1_024, heapMib = 1_024, cpuCores = 4),
            perWorkerBytes = 45L * MIB,
            hardCap = 8
        )
        val memoryLimited = DeviceResourcePolicy.recommendConcurrency(
            snapshot = snapshot(systemMib = 200, heapMib = 200, cpuCores = 12),
            perWorkerBytes = 45L * MIB,
            hardCap = 8
        )

        assertEquals(3, cpuLimited)
        assertEquals(2, memoryLimited)
    }

    @Test
    fun `ocr recommendation accounts for a shared model budget`() {
        val recommendation = DeviceResourcePolicy.recommendConcurrency(
            snapshot = snapshot(systemMib = 256, heapMib = 256, cpuCores = 8),
            perWorkerBytes = DeviceResourcePolicy.OCR_INSTANCE_BYTES,
            hardCap = 8
        )

        assertEquals(3, recommendation)
    }

    @Test
    fun `manual concurrency above recommendation warns`() {
        val assessment = DeviceResourcePolicy.assessConcurrency(
            snapshot = snapshot(systemMib = 512, heapMib = 512, cpuCores = 4),
            perWorkerBytes = 45L * MIB,
            requestedConcurrency = 5,
            hardCap = 8
        )

        assertEquals(3, assessment.recommendedConcurrency)
        assertTrue(assessment.shouldWarn)
    }

    @Test
    fun `bitmap scale is lowered instead of rejecting a large PDF page`() {
        val fitted = DeviceResourcePolicy.fitScaleToPixelBudget(
            width = 4_000,
            height = 8_000,
            preferredScale = 2f,
            maxPixels = 32_000_000L
        )

        assertEquals(1f, fitted, 0.001f)
    }

    @Test
    fun `PDF risk uses preferred bitmap size before render scale is capped`() {
        val snapshot = snapshot(systemMib = 600, heapMib = 400, cpuCores = 8)
        val preferredBytes = DeviceResourcePolicy.estimateBitmapBytes(4_000, 8_000, 2f)
        val safePixels = DeviceResourcePolicy.safeBitmapPixelBudget(snapshot)
        val fittedScale = DeviceResourcePolicy.fitScaleToPixelBudget(
            width = 4_000,
            height = 8_000,
            preferredScale = 2f,
            maxPixels = safePixels
        )
        val fittedBytes = DeviceResourcePolicy.estimateBitmapBytes(4_000, 8_000, fittedScale)

        assertTrue(DeviceResourcePolicy.assess(snapshot, preferredBytes).shouldWarn)
        assertFalse(DeviceResourcePolicy.assess(snapshot, fittedBytes).shouldWarn)
    }

    @Test
    fun `resource estimates saturate instead of overflowing`() {
        assertEquals(
            Long.MAX_VALUE,
            DeviceResourcePolicy.saturatingAdd(Long.MAX_VALUE - 10L, 20L)
        )
    }

    private fun snapshot(systemMib: Long, heapMib: Long, cpuCores: Int) =
        DeviceResourceSnapshot(
            systemAvailableBytes = systemMib * MIB,
            appHeapHeadroomBytes = heapMib * MIB,
            systemReportsLowMemory = false,
            cpuCoreCount = cpuCores
        )

    private companion object {
        const val MIB = 1024L * 1024L
    }
}
