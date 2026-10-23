package com.openappslabs.fiveg.automation

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.IBinder
import android.util.Log
import com.openappslabs.fiveg.services.FiveGAccessibilityService
import android.telephony.SubscriptionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.SystemServiceHelper

data class ShizukuState(
    val isInstalled: Boolean = false,
    val isRunning: Boolean = false,
    val isPermissionGranted: Boolean = false
)

/**
 * Controller for elevated background automation via Shizuku.
 * Can execute silent modem switching or auto-grant Accessibility permissions.
 */
object ShizukuController {

    private const val TAG = "ShizukuController"
    const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
    const val PERMISSION_REQUEST_CODE = 5001

    // Android AOSP Telephony Network Mode Constants
    // 26 = NR_ONLY (Pure 5G Standalone) or 23 on some devices
    const val NETWORK_MODE_NR_ONLY = 26
    const val NETWORK_MODE_NR_ONLY_ALT = 23

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

    private val permissionResultListener = Shizuku.OnRequestPermissionResultListener { requestCode, _ ->
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
        if (_state.value.isRunning && _state.value.isPermissionGranted) {
            unhideApis()
        }
    }

    fun unhideApis() {
        try {
            executeShell("settings put global hidden_api_policy 1")
            executeShell("settings put global hidden_api_policy_p_apps 1")
            executeShell("settings put global hidden_api_policy_pre_p_apps 1")
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to unhide APIs: ${e.message}")
        }
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
     * Automatically enables FiveGAccessibilityService using Shizuku shell.
     * Works without opening Android system settings.
     */
    fun enableAccessibilityServiceViaShizuku(context: Context): Boolean {
        if (!_state.value.isRunning || !_state.value.isPermissionGranted) {
            Log.w(TAG, "Shizuku not ready to enable accessibility service")
            return false
        }
        return try {
            val component = ComponentName(context, FiveGAccessibilityService::class.java).flattenToString()
            val current = executeShell("settings get secure enabled_accessibility_services")
            val newServices = if (current.isBlank() || current.contains("null", ignoreCase = true) || current.startsWith("error")) {
                component
            } else if (!current.contains(component)) {
                "$current:$component"
            } else {
                current
            }
            executeShell("settings put secure enabled_accessibility_services $newServices")
            executeShell("settings put secure accessibility_enabled 1")
            Log.i(TAG, "Enabled accessibility service via Shizuku: $component")
            true
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to enable accessibility via Shizuku: ${e.message}", e)
            false
        }
    }

    /**
     * Executes silent 5G mode change using:
     * 1. Official AOSP telephony command: cmd phone set-allowed-network-types-for-users
     * 2. Direct ITelephony Binder call via ShizukuBinderWrapper (running as UID 2000 com.android.shell)
     * 3. Settings.Global fallbacks for Vivo/Qualcomm/AOSP
     */
    suspend fun setNetworkModeSilently(context: Context, targetMode: TargetMode): Boolean = withContext(Dispatchers.IO) {
        if (!state.value.isRunning || !state.value.isPermissionGranted) {
            Log.w(TAG, "Shizuku not ready for silent switching")
            return@withContext false
        }

        unhideApis()

        // Determine active subscription IDs
        val subIds = mutableSetOf<Int>()
        try {
            val defaultDataSubId = SubscriptionManager.getDefaultDataSubscriptionId()
            if (defaultDataSubId != SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
                subIds.add(defaultDataSubId)
            }
            val defaultSubId = SubscriptionManager.getDefaultSubscriptionId()
            if (defaultSubId != SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
                subIds.add(defaultSubId)
            }
            val subManager = context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as? SubscriptionManager
            subManager?.activeSubscriptionInfoList?.forEach { info ->
                subIds.add(info.subscriptionId)
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to resolve subscriptions: ${e.message}")
        }
        subIds.addAll(listOf(0, 1, 2))

        // Bitmasks for Android 11+:
        // NR Only = 1L shl 19 (524288L)
        // Normal hybrid = (1L shl 31) - 1 (2147483647L)
        val bitmask = when (targetMode) {
            TargetMode.NR_ONLY -> (1L shl 19)
            TargetMode.NORMAL_AUTO -> (1L shl 31) - 1
        }

        val primaryModeCode = when (targetMode) {
            TargetMode.NR_ONLY -> NETWORK_MODE_NR_ONLY
            TargetMode.NORMAL_AUTO -> NETWORK_MODE_NORMAL_HYBRID_ALL
        }

        var cmdSuccess = false
        var binderSuccess = false
        var settingsSuccess = false

        // Strategy 1: Official AOSP Telephony Shell Command (cmd phone set-allowed-network-types-for-users)
        try {
            val outDefault = executeShell("cmd phone set-allowed-network-types-for-users $bitmask")
            Log.i(TAG, "cmd phone (default) => '$outDefault'")
            if (!outDefault.startsWith("error") && !outDefault.contains("SecurityException", ignoreCase = true) && !outDefault.contains("Failure", ignoreCase = true)) {
                cmdSuccess = true
            }

            for (slot in 0..1) {
                val outSlot = executeShell("cmd phone set-allowed-network-types-for-users -s $slot $bitmask")
                Log.i(TAG, "cmd phone -s $slot => '$outSlot'")
                if (!outSlot.startsWith("error") && !outSlot.contains("SecurityException", ignoreCase = true) && !outSlot.contains("Failure", ignoreCase = true)) {
                    cmdSuccess = true
                }
            }

            for (subId in subIds) {
                if (subId > 1) {
                    val outSub = executeShell("cmd phone set-allowed-network-types-for-users -s $subId $bitmask")
                    Log.i(TAG, "cmd phone -s $subId => '$outSub'")
                    if (!outSub.startsWith("error") && !outSub.contains("SecurityException", ignoreCase = true) && !outSub.contains("Failure", ignoreCase = true)) {
                        cmdSuccess = true
                    }
                }
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Strategy 1 (cmd phone) failed: ${e.message}", e)
        }

        // Strategy 2: ITelephony via ShizukuBinderWrapper
        try {
            for (subId in subIds) {
                if (setNetworkModeViaShizukuBinder(subId, targetMode)) {
                    binderSuccess = true
                }
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Strategy 2 (ITelephony Binder) failed: ${e.message}", e)
        }

        // Strategy 3: Global System Settings fallback (Vivo / Qualcomm / AOSP)
        try {
            val globalKeys = listOf(
                "preferred_network_mode",
                "preferred_network_mode0",
                "preferred_network_mode1",
                "preferred_network_mode2",
                "preferred_network_mode_sub1",
                "preferred_network_mode_sub2",
                "vivo_preferred_network_mode",
                "vivo_preferred_network_mode1",
                "vivo_preferred_network_mode2",
                "user_network_type",
                "user_network_type1",
                "user_network_type2"
            )
            for (key in globalKeys) {
                executeShell("settings put global $key $primaryModeCode")
            }
            for (subId in subIds) {
                executeShell("settings put global preferred_network_mode$subId $primaryModeCode")
                executeShell("settings put global preferred_network_mode_sub$subId $primaryModeCode")
            }
            executeShell("settings put global allowed_network_types_for_reasons $bitmask")
            executeShell("settings put global allowed_network_types_for_reasons_sub1 $bitmask")
            executeShell("settings put global allowed_network_types_for_reasons_sub2 $bitmask")
            settingsSuccess = true
        } catch (e: Throwable) {
            Log.d(TAG, "Strategy 3 (Settings put) failed: ${e.message}")
        }

        val finalResult = cmdSuccess || binderSuccess || settingsSuccess
        Log.i(TAG, "setNetworkModeSilently finished: cmdSuccess=$cmdSuccess, binderSuccess=$binderSuccess, settingsSuccess=$settingsSuccess => result=$finalResult")
        return@withContext finalResult
    }

    /**
     * Direct ITelephony call wrapped with ShizukuBinderWrapper.
     * Uses com.android.shell calling package because Shizuku executes under UID 2000.
     */
    private fun setNetworkModeViaShizukuBinder(subId: Int, targetMode: TargetMode): Boolean {
        return try {
            val rawBinder = SystemServiceHelper.getSystemService("phone") ?: return false
            val wrappedBinder = ShizukuBinderWrapper(rawBinder)
            val stubClass = Class.forName("com.android.internal.telephony.ITelephony\$Stub")
            val asInterface = stubClass.getMethod("asInterface", IBinder::class.java)
            val iTelephony = asInterface.invoke(null, wrappedBinder) ?: return false

            val bitmask = when (targetMode) {
                TargetMode.NR_ONLY -> (1L shl 19)
                TargetMode.NORMAL_AUTO -> (1L shl 31) - 1
            }

            val modeCode = when (targetMode) {
                TargetMode.NR_ONLY -> NETWORK_MODE_NR_ONLY
                TargetMode.NORMAL_AUTO -> NETWORK_MODE_NORMAL_HYBRID_ALL
            }

            val methods = iTelephony.javaClass.methods
            val shellPackage = "com.android.shell"
            var success = false

            // Strategy 2a: setAllowedNetworkTypesForReason (Android 11+)
            for (m in methods.filter { it.name == "setAllowedNetworkTypesForReason" }) {
                try {
                    when {
                        m.parameterCount == 3 -> {
                            m.invoke(iTelephony, subId, 0, bitmask)
                            Log.i(TAG, "setAllowedNetworkTypesForReason(subId=$subId, 0, $bitmask) SUCCESS")
                            success = true
                        }
                        m.parameterCount == 4 && m.parameterTypes[3] == String::class.java -> {
                            m.invoke(iTelephony, subId, 0, bitmask, shellPackage)
                            Log.i(TAG, "setAllowedNetworkTypesForReason(subId=$subId, 0, $bitmask, pkg) SUCCESS")
                            success = true
                        }
                    }
                } catch (e: Throwable) {
                    Log.d(TAG, "setAllowedNetworkTypesForReason attempt failed: ${e.message}")
                }
            }

            // Strategy 2b: setAllowedNetworkTypes (Android 11/12)
            if (!success) {
                for (m in methods.filter { it.name == "setAllowedNetworkTypes" }) {
                    try {
                        when {
                            m.parameterCount == 2 -> {
                                m.invoke(iTelephony, subId, bitmask)
                                Log.i(TAG, "setAllowedNetworkTypes(subId=$subId, $bitmask) SUCCESS")
                                success = true
                            }
                            m.parameterCount == 3 && m.parameterTypes[1].name == "long" -> {
                                m.invoke(iTelephony, subId, bitmask, shellPackage)
                                Log.i(TAG, "setAllowedNetworkTypes(subId=$subId, $bitmask, pkg) SUCCESS")
                                success = true
                            }
                        }
                    } catch (e: Throwable) {
                        Log.d(TAG, "setAllowedNetworkTypes attempt failed: ${e.message}")
                    }
                }
            }

            // Strategy 2c: setPreferredNetworkType (Legacy fallback)
            if (!success) {
                for (m in methods.filter { it.name == "setPreferredNetworkType" }) {
                    try {
                        when (m.parameterCount) {
                            2 -> {
                                m.invoke(iTelephony, subId, modeCode)
                                Log.i(TAG, "setPreferredNetworkType(subId=$subId, $modeCode) SUCCESS")
                                success = true
                            }
                            3 -> {
                                m.invoke(iTelephony, subId, modeCode, shellPackage)
                                Log.i(TAG, "setPreferredNetworkType(subId=$subId, $modeCode, pkg) SUCCESS")
                                success = true
                            }
                        }
                    } catch (e: Throwable) {
                        Log.d(TAG, "setPreferredNetworkType attempt failed: ${e.message}")
                    }
                }
            }

            success
        } catch (e: Throwable) {
            Log.e(TAG, "setNetworkModeViaShizukuBinder failed: ${e.message}", e)
            false
        }
    }

    fun executeShell(command: String): String {
        return try {
            val process = Shizuku.newProcess(arrayOf("sh", "-c", command), null, null)
            val output = process.inputStream.bufferedReader().use { it.readText() }
            val error = process.errorStream.bufferedReader().use { it.readText() }
            val exitCode = process.waitFor()
            if (error.isNotBlank() && exitCode != 0) {
                Log.d(TAG, "Shell cmd '$command' exited with $exitCode: $error")
            }
            if (output.isNotBlank()) output.trim() else error.trim()
        } catch (e: Throwable) {
            try {
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
                ) as java.lang.Process
                val output = process.inputStream.bufferedReader().use { it.readText() }
                process.waitFor()
                output.trim()
            } catch (ex: Throwable) {
                Log.e(TAG, "Shell execution failed: ${ex.message}", ex)
                "error: ${ex.message}"
            }
        }
    }
}
