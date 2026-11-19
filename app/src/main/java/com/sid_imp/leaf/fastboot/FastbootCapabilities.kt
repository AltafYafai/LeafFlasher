package com.sid_imp.leaf.fastboot

import kotlinx.coroutines.CancellationException

/**
 * Capability probes that can be safely run while the device is still in the bootloader.
 *
 * Devices with Android dynamic partitions expose a `super` partition and can boot into
 * userspace fastboot (fastbootd). Older/legacy devices keep `system`, `vendor`, etc. as
 * physical partitions and flash those directly from the bootloader.
 */
object FastbootCapabilities {

    private val SUPER_PARTITION_PROBES = listOf(
        "partition-size:super",
        "partition-type:super"
    )

    private val PHYSICAL_SYSTEM_TYPE_PROBES = listOf(
        "partition-type:system",
        "partition-type:system_a"
    )

    private val PHYSICAL_SYSTEM_SIZE_PROBES = listOf(
        "partition-size:system",
        "partition-size:system_a"
    )

    /**
     * Detects whether the connected device needs (and supports) fastbootd for logical
     * partitions.
     *
     * @return `true` when a dynamic `super` partition or fastbootd is available,
     *         `false` when physical system partitions were found (legacy layout), or
     *         `null` when the bootloader did not answer any capability probe.
     */
    suspend fun supportsDynamicPartitions(device: FastbootDevice): Boolean? {
        try {
            if (device.currentMode == FastbootMode.FASTBOOTD) return true

            val userspace = device.getVariable("is-userspace")
            if (userspace.equals("yes", ignoreCase = true)) return true

            // Strongest positive signal: the bootloader exposes the super partition name.
            if (device.getVariable("super-partition-name")?.isNotBlank() == true) return true

            // Some bootloaders omit super-partition-name but still expose the super partition itself.
            for (probe in SUPER_PARTITION_PROBES) {
                if (device.getVariable(probe)?.isNotBlank() == true) return true
            }

            // Logical partitions inside super are not addressable from the bootloader, so a
            // readable physical system partition means the legacy bootloader path applies.
            for (probe in PHYSICAL_SYSTEM_TYPE_PROBES) {
                if (device.getVariable(probe)?.isNotBlank() == true) return false
            }
            for (probe in PHYSICAL_SYSTEM_SIZE_PROBES) {
                if (parsePositiveSize(device.getVariable(probe))) return false
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Treat USB hiccups as unknown so the existing fastbootd flow can still try.
        }
        return null
    }

    private fun parsePositiveSize(raw: String?): Boolean {
        val value = raw?.trim().orEmpty()
        if (value.isEmpty()) return false
        val size = if (value.startsWith("0x", ignoreCase = true)) {
            value.substring(2).toLongOrNull(16)
        } else {
            value.toLongOrNull()
        }
        return size != null && size > 0L
    }
}
