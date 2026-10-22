package com.openappslabs.fiveg.automation

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.text.TextUtils
import android.util.Log
import com.openappslabs.fiveg.services.FiveGAccessibilityService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class TargetMode {
    NR_ONLY,
    NORMAL_AUTO
}

enum class CurrentNetworkState {
    NR_ONLY_LOCKED,
    NORMAL_AUTO,
    UNKNOWN
}

enum class EngineMode {
    AUTO,          // Smart Fallback: Shizuku (silent) if available, else Accessibility Service
    SHIZUKU_ONLY,  // Pure silent background switching only
    A11Y_ONLY      // Standard 1-second Accessibility Service automation only
}

sealed class AutomationStatus {
    object Idle : AutomationStatus()
    data class Running(val target: TargetMode, val startTimeMs: Long, val engineName: String = "") : AutomationStatus()
    data class Success(val target: TargetMode, val elapsedMs: Long, val engineName: String = "") : AutomationStatus()
    data class Timeout(val message: String) : AutomationStatus()
    data class Error(val message: String) : AutomationStatus()
}

/**
 * Thread-safe singleton controller managing dual-engine 5G network mode switching.
 * Supports Shizuku (silent 50ms) and Accessibility Service (1-second UI automation).
 */
object AutomationController {

    private const val TAG = "AutomationController"
    private const val PREFS_NAME = "five_g_automation_prefs"
    private const val KEY_NETWORK_MODE = "saved_network_mode"
    private const val KEY_ENGINE_MODE = "saved_engine_mode"
    private const val TIMEOUT_SAFEGUARD_MS = 4000L

    val NORMAL_AUTO_PRIORITY_LIST = listOf(
        "NR/LTE/TDSCDMA/CDMA/EvDo/GSM/WCDMA",
        "NR/LTE/GSM/WCDMA",
        "NR/LTE"
    )

    private val scope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())
    private var timeoutJob: Job? = null

    @Volatile
    var targetNetworkMode: TargetMode? = null
        private set

    @Volatile
    private var switchStartTimeMs: Long = 0L

    @Volatile
    private var activeEngineUsed: String = "Accessibility"

    private val _currentNetworkState = MutableStateFlow(CurrentNetworkState.UNKNOWN)
    val currentNetworkState: StateFlow<CurrentNetworkState> = _currentNetworkState.asStateFlow()

    private val _selectedEngine = MutableStateFlow(EngineMode.AUTO)
    val selectedEngine: StateFlow<EngineMode> = _selectedEngine.asStateFlow()

    private val _automationStatus = MutableStateFlow<AutomationStatus>(AutomationStatus.Idle)
    val automationStatus: StateFlow<AutomationStatus> = _automationStatus.asStateFlow()

    private val _isAccessibilityEnabled = MutableStateFlow(false)
    val isAccessibilityEnabled: StateFlow<Boolean> = _isAccessibilityEnabled.asStateFlow()

    fun init(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val savedMode = prefs.getString(KEY_NETWORK_MODE, null)
        _currentNetworkState.value = when (savedMode) {
            TargetMode.NR_ONLY.name -> CurrentNetworkState.NR_ONLY_LOCKED
            TargetMode.NORMAL_AUTO.name -> CurrentNetworkState.NORMAL_AUTO
            else -> CurrentNetworkState.UNKNOWN
        }

        val savedEngine = prefs.getString(KEY_ENGINE_MODE, EngineMode.AUTO.name)
        _selectedEngine.value = try {
            EngineMode.valueOf(savedEngine ?: EngineMode.AUTO.name)
        } catch (e: Exception) {
            EngineMode.AUTO
        }

        ShizukuController.init(context)
        checkAccessibilityEnabled(context)
    }

    fun setEngineMode(context: Context, mode: EngineMode) {
        _selectedEngine.value = mode
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_ENGINE_MODE, mode.name).apply()
        Log.i(TAG, "Automation engine set to: $mode")
    }

    fun checkAccessibilityEnabled(context: Context): Boolean {
        val enabled = isAccessibilityServiceEnabled(context)
        _isAccessibilityEnabled.value = enabled
        return enabled
    }

    fun isAccessibilityServiceEnabled(context: Context): Boolean {
        val expectedComponentName = ComponentName(context, FiveGAccessibilityService::class.java).flattenToString()
        val shortComponentName = ComponentName(context, FiveGAccessibilityService::class.java).flattenToShortString()

        val enabledServices = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false

        val colonSplitter = TextUtils.SimpleStringSplitter(':')
        colonSplitter.setString(enabledServices)
        while (colonSplitter.hasNext()) {
            val component = colonSplitter.next()
            if (component.equals(expectedComponentName, ignoreCase = true) ||
                component.equals(shortComponentName, ignoreCase = true) ||
                component.contains(FiveGAccessibilityService::class.java.simpleName, ignoreCase = true)
            ) {
                return true
            }
        }
        return false
    }

    /**
     * Executes START 5G Lock (NR Only) via selected engine (Shizuku, A11y, or Auto).
     */
    @Synchronized
    fun start5GLock(context: Context): Boolean {
        return executeModeSwitch(context, TargetMode.NR_ONLY)
    }

    /**
     * Executes STOP 5G Lock (Restore Normal Mode) via selected engine.
     */
    @Synchronized
    fun stop5GLock(context: Context): Boolean {
        return executeModeSwitch(context, TargetMode.NORMAL_AUTO)
    }

    private fun executeModeSwitch(context: Context, target: TargetMode): Boolean {
        switchStartTimeMs = System.currentTimeMillis()
        val engine = _selectedEngine.value

        when (engine) {
            EngineMode.SHIZUKU_ONLY -> {
                val state = ShizukuController.checkState(context)
                if (!state.isRunning || !state.isPermissionGranted) {
                    _automationStatus.value = AutomationStatus.Error("Shizuku is not running or permission is missing.")
                    return false
                }
                activeEngineUsed = "Shizuku (Silent)"
                _automationStatus.value = AutomationStatus.Running(target, switchStartTimeMs, activeEngineUsed)
                executeShizukuSilent(context, target)
                return true
            }

            EngineMode.A11Y_ONLY -> {
                if (!checkAccessibilityEnabled(context)) {
                    val sState = ShizukuController.checkState(context)
                    if (sState.isRunning && sState.isPermissionGranted) {
                        ShizukuController.enableAccessibilityServiceViaShizuku(context)
                        try { Thread.sleep(300) } catch (_: Exception) {}
                        checkAccessibilityEnabled(context)
                    }
                }
                if (!checkAccessibilityEnabled(context)) {
                    _automationStatus.value = AutomationStatus.Error("Accessibility Service is disabled. Please enable it.")
                    return false
                }
                activeEngineUsed = "Accessibility (1-Sec UI)"
                return executeAccessibilitySwitch(context, target)
            }

            EngineMode.AUTO -> {
                val shizukuState = ShizukuController.checkState(context)
                if (shizukuState.isRunning && shizukuState.isPermissionGranted) {
                    activeEngineUsed = "Shizuku (Silent Auto)"
                    _automationStatus.value = AutomationStatus.Running(target, switchStartTimeMs, activeEngineUsed)
                    executeShizukuSilent(context, target, fallbackToA11y = true)
                    return true
                } else {
                    // Fallback to Accessibility Service
                    if (!checkAccessibilityEnabled(context)) {
                        _automationStatus.value = AutomationStatus.Error("Enable Shizuku OR Accessibility Service to automate switching.")
                        return false
                    }
                    activeEngineUsed = "Accessibility (Fallback)"
                    return executeAccessibilitySwitch(context, target)
                }
            }
        }
    }

    private fun executeShizukuSilent(context: Context, target: TargetMode, fallbackToA11y: Boolean = false) {
        scope.launch {
            val success = ShizukuController.setNetworkModeSilently(target)
            if (success) {
                onAutomationSuccess(context, target)
            } else if (fallbackToA11y) {
                Log.d(TAG, "Shizuku silent switch not applied by modem, attempting Accessibility fallback...")
                if (!isAccessibilityServiceEnabled(context)) {
                    val enabledViaShizuku = ShizukuController.enableAccessibilityServiceViaShizuku(context)
                    if (enabledViaShizuku) {
                        delay(350)
                        checkAccessibilityEnabled(context)
                    }
                }

                if (isAccessibilityServiceEnabled(context)) {
                    activeEngineUsed = "Accessibility (Smart Fallback)"
                    withContext(Dispatchers.Main) {
                        executeAccessibilitySwitch(context, target)
                    }
                } else {
                    _automationStatus.value = AutomationStatus.Error(
                        "Silent switch not supported on this phone. Please enable Accessibility Service for 1-second auto switch."
                    )
                }
            } else {
                _automationStatus.value = AutomationStatus.Error(
                    "Silent switch not supported on this phone. Select Auto or Accessibility Engine."
                )
            }
        }
    }

    private fun executeAccessibilitySwitch(context: Context, target: TargetMode): Boolean {
        targetNetworkMode = target
        _automationStatus.value = AutomationStatus.Running(target, switchStartTimeMs, activeEngineUsed)

        armTimeoutSafeguard("Switching to $target timed out after 4 seconds.")
        return launchRadioInfo(context)
    }

    private fun armTimeoutSafeguard(timeoutMsg: String) {
        timeoutJob?.cancel()
        timeoutJob = scope.launch {
            delay(TIMEOUT_SAFEGUARD_MS)
            synchronized(this@AutomationController) {
                if (targetNetworkMode != null) {
                    Log.w(TAG, "Automation safeguard triggered: $timeoutMsg")
                    targetNetworkMode = null
                    _automationStatus.value = AutomationStatus.Timeout(timeoutMsg)
                }
            }
        }
    }

    @Synchronized
    fun onAutomationSuccess(context: Context, mode: TargetMode) {
        timeoutJob?.cancel()
        timeoutJob = null
        targetNetworkMode = null

        val elapsed = if (switchStartTimeMs > 0) System.currentTimeMillis() - switchStartTimeMs else 0L
        switchStartTimeMs = 0L

        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_NETWORK_MODE, mode.name).apply()

        _currentNetworkState.value = when (mode) {
            TargetMode.NR_ONLY -> CurrentNetworkState.NR_ONLY_LOCKED
            TargetMode.NORMAL_AUTO -> CurrentNetworkState.NORMAL_AUTO
        }
        _automationStatus.value = AutomationStatus.Success(mode, elapsed, activeEngineUsed)
        Log.i(TAG, "Automation completed successfully via $activeEngineUsed for $mode in ${elapsed}ms")
    }

    @Synchronized
    fun clearTargetMode() {
        timeoutJob?.cancel()
        timeoutJob = null
        targetNetworkMode = null
    }

    fun launchRadioInfo(context: Context): Boolean {
        val candidates = listOf(
            ComponentName("com.android.phone", "com.android.phone.settings.RadioInfo"),
            ComponentName("com.android.settings", "com.android.settings.RadioInfo"),
            ComponentName("com.android.settings", "com.android.settings.Settings\$RadioInfoActivity"),
            ComponentName("com.android.settings", "com.android.settings.TestingSettings"),
            ComponentName("com.android.phone", "com.android.phone.RadioInfo")
        )

        for (targetComponent in candidates) {
            try {
                val intent = Intent(Intent.ACTION_MAIN).apply {
                    component = targetComponent
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_CLEAR_TOP or
                            Intent.FLAG_ACTIVITY_NO_ANIMATION
                }
                context.startActivity(intent)
                Log.d(TAG, "Successfully launched RadioInfo via $targetComponent")
                return true
            } catch (e: Exception) {
                Log.d(TAG, "Component $targetComponent not available: ${e.message}")
            }
        }

        try {
            val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:*#*#4636#*#*")).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION
            }
            context.startActivity(intent)
            Log.d(TAG, "Fallback dialer code launched.")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "All RadioInfo launch mechanisms failed: ${e.message}", e)
            _automationStatus.value = AutomationStatus.Error("Could not launch RadioInfo on this device.")
            clearTargetMode()
            return false
        }
    }
}
