package com.openappslabs.fiveg.automation

import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku

data class ShizukuState(
    val isInstalled: Boolean = false,
    val isRunning: Boolean = false,
    val isPermissionGranted: Boolean = false
)

/**
 * Controller for silent, elevated background automation via Shizuku.
 * Executes without opening RadioInfo or flashing the screen (~50ms execution).
 */
object ShizukuController {

    private const val TAG = "ShizukuController"
    const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
    const val PERMISSION_REQUEST_CODE = 5001

    // Android AOSP Telephony Network Mode Constants
    // 26 = NR_ONLY (Pure 5G Standalone)
    const val NETWORK_MODE_NR_ONLY = 26

    // 33 = NR_LTE_TDSCDMA_CDMA_EVDO_GSM_WCDMA (Complete hybrid)
    // 20 = NR_LTE_GSM_WCDMA
    const val NETWORK_MODE_NORMAL_HYBRID_ALL = 33
    const val NETWORK_MODE_NORMAL_HYBRID_GSM = 20

    private val _state = MutableStateFlow(ShizukuState())
    val state: StateFlow<ShizukuState> = _state.asStateFlow()

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        checkState()
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        checkState()
    }

    private val permissionResultListener = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
        if (requestCode == PERMISSION_REQUEST_CODE) {
            checkState()
        }
    }

    fun init(context: Context) {
        try {
            Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
            Shizuku.addBinderDeadListener(binderDeadListener)
            Shizuku.addRequestPermissionResultListener(permissionResultListener)
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to register Shizuku listeners: ${e.message}")
        }
        checkState(context)
    }

    fun checkState(context: Context? = null): ShizukuState {
        val installed = if (context != null) isShizukuInstalled(context) else _state.value.isInstalled
        val running = try {
            Shizuku.pingBinder()
        } catch (e: Throwable) {
            false
        }

        val granted = if (running) {
            try {
                Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
            } catch (e: Throwable) {
                false
            }
        } else {
            false
        }

        val newState = ShizukuState(
            isInstalled = installed,
            isRunning = running,
            isPermissionGranted = granted
        )
        _state.value = newState
        return newState
    }

    fun isShizukuInstalled(context: Context): Boolean {
        return try {
            context.packageManager.getPackageInfo(SHIZUKU_PACKAGE, 0)
            true
        } catch (e: Exception) {
            false
        }
    }

    fun requestPermission(activity: Activity) {
        if (!_state.value.isRunning) {
            Log.w(TAG, "Cannot request permission: Shizuku service is not running")
            return
        }
        try {
            Shizuku.requestPermission(PERMISSION_REQUEST_CODE)
        } catch (e: Throwable) {
            Log.e(TAG, "Error requesting Shizuku permission: ${e.message}", e)
        }
    }

    /**
     * Executes silent 5G mode change using elevated shell commands.
     * Zero UI disruption, no screen switching, finishes in ~50ms.
     */
    suspend fun setNetworkModeSilently(targetMode: TargetMode): Boolean = withContext(Dispatchers.IO) {
        if (!state.value.isRunning || !state.value.isPermissionGranted) {
            Log.w(TAG, "Shizuku not ready for silent switching")
            return@withContext false
        }

        val primaryModeCode = when (targetMode) {
            TargetMode.NR_ONLY -> NETWORK_MODE_NR_ONLY
            TargetMode.NORMAL_AUTO -> NETWORK_MODE_NORMAL_HYBRID_ALL
        }

        val fallbackModeCode = when (targetMode) {
            TargetMode.NR_ONLY -> 26
            TargetMode.NORMAL_AUTO -> NETWORK_MODE_NORMAL_HYBRID_GSM
        }

        // Apply command across subId 0, 1, 2 to cover primary and secondary SIMs
        val subIds = listOf(0, 1, 2)
        var success = false

        for (subId in subIds) {
            val cmd1 = "cmd phone set-preferred-network-type $subId $primaryModeCode"
            val res1 = executeShell(cmd1)
            Log.d(TAG, "Executed: $cmd1 -> Output: $res1")

            if (res1.contains("error", ignoreCase = true) || res1.contains("unknown", ignoreCase = true)) {
                // Try fallback mode code
                val cmdFallback = "cmd phone set-preferred-network-type $subId $fallbackModeCode"
                val resFallback = executeShell(cmdFallback)
                Log.d(TAG, "Fallback executed: $cmdFallback -> Output: $resFallback")
            } else {
                success = true
            }
        }

        // Secondary fallback: global settings preferred network mode
        try {
            executeShell("settings put global preferred_network_mode $primaryModeCode")
        } catch (e: Exception) {
            Log.d(TAG, "Settings put error (non-fatal): ${e.message}")
        }

        return@withContext success
    }

    private fun executeShell(command: String): String {
        return try {
            val clazz = Class.forName("rikka.shizuku.Shizuku")
            val newProcessMethod = clazz.getDeclaredMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java
            )
            newProcessMethod.isAccessible = true
            val process = newProcessMethod.invoke(
                null,
                arrayOf("sh", "-c", command),
                null,
                null
            ) as Process
            val output = process.inputStream.bufferedReader().use { it.readText() }
            process.waitFor()
            output.trim()
        } catch (e: Throwable) {
            Log.e(TAG, "Shell execution failed: ${e.message}", e)
            "error: ${e.message}"
        }
    }
}
