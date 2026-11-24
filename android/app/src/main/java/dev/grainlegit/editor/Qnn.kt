package dev.grainlegit.editor

import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtEpDevice
import android.system.Os
import java.io.File

internal object Qnn {
    private const val PROVIDER = "QNNExecutionProvider"
    private var device: OrtEpDevice? = null
    private var registered = false

    @JvmStatic
    @Synchronized
    fun npu(libraryDir: File): OrtEpDevice {
        device?.let { return it }
        val runtime = OrtEnvironment.getEnvironment()
        if (!registered) {
            val dspPath = listOfNotNull(libraryDir.absolutePath, Os.getenv("ADSP_LIBRARY_PATH")).joinToString(";")
            Os.setenv("ADSP_LIBRARY_PATH", dspPath, true)
            runtime.registerExecutionProviderLibrary(PROVIDER, File(libraryDir, "libonnxruntime_providers_qnn.so").absolutePath)
            registered = true
        }
        return checkNotNull(runtime.epDevices.firstOrNull { it.epName == PROVIDER && it.device.type.name == "NPU" }) {
            "QNN did not expose the Hexagon NPU"
        }.also { device = it }
    }
}
