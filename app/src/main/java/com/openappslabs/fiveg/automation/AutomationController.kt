package com.openappslabs.fiveg.automation

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
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

/**
 * Target mode requested for automated switching.
 */
enum class TargetMode {
    NR_ONLY,
    NORMAL_AUTO
}

/**
 * Current network automation lock status displayed in UI & Quick Settings Tile.
 */
enum class CurrentNetworkState {
    NR_ONLY_LOCKED,
    NORMAL_AUTO,
    UNKNOWN
}

/**
 * State of the automated switching execution.
 */
sealed class AutomationStatus {
    object Idle : AutomationStatus()
    data class Running(val target: TargetMode, val startTimeMs: Long) : AutomationStatus()
    data class Success(val target: TargetMode, val elapsedMs: Long) : AutomationStatus()
    data class Timeout(val message: String) : AutomationStatus()
    data class Error(val message: String) : AutomationStatus()
}

/**
 * Thread-safe singleton controller managing automated 5G network mode switching.
 */
object AutomationController {

    private const val TAG = "AutomationController"
    private const val PREFS_NAME = "five_g_automation_prefs"
    private const val KEY_NETWORK_MODE = "saved_network_mode"
    private const val TIMEOUT_SAFEGUARD_MS = 4000L

    // Priority list for NORMAL_AUTO mode selection in RadioInfo spinner popup
    val NORMAL_AUTO_PRIORITY_LIST = listOf(
        "NR/LTE/TDSCDMA/CDMA/EvDo/GSM/WCDMA",
        "NR/LTE/GSM/WCDMA",
        "NR/LTE"
    )

    private val scope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())
    private var timeoutJob: Job? = null

    // Volatile target network mode inspected by FiveGAccessibilityService
    @Volatile
    var targetNetworkMode: TargetMode? = null
        private set

    @Volatile
    private var switchStartTimeMs: Long = 0L

    // Reactive StateFlows for Jetpack Compose UI & Quick Settings Tile
    private val _currentNetworkState = MutableStateFlow(CurrentNetworkState.UNKNOWN)
    val currentNetworkState: StateFlow<CurrentNetworkState> = _currentNetworkState.asStateFlow()

    private val _automationStatus = MutableStateFlow<AutomationStatus>(AutomationStatus.Idle)
    val automationStatus: StateFlow<AutomationStatus> = _automationStatus.asStateFlow()

    private val _isAccessibilityEnabled = MutableStateFlow(false)
    val isAccessibilityEnabled: StateFlow<Boolean> = _isAccessibilityEnabled.asStateFlow()

    /**
     * Initialize saved preferences state and verify accessibility status.
     */
    fun init(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val saved = prefs.getString(KEY_NETWORK_MODE, null)
        _currentNetworkState.value = when (saved) {
            TargetMode.NR_ONLY.name -> CurrentNetworkState.NR_ONLY_LOCKED
            TargetMode.NORMAL_AUTO.name -> CurrentNetworkState.NORMAL_AUTO
            else -> CurrentNetworkState.UNKNOWN
        }
        checkAccessibilityEnabled(context)
    }

    /**
     * Check whether FiveGAccessibilityService is currently enabled in System Settings.
     */
    fun checkAccessibilityEnabled(context: Context): Boolean {
        val enabled = isAccessibilityServiceEnabled(context)
        _isAccessibilityEnabled.value = enabled
        return enabled
    }

    /**
     * Inspect system Secure Settings to determine if our accessibility service is turned on.
     */
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
     * START 5G Lock (NR Only) flow:
     * Sets target to NR_ONLY, arms 4-second safeguard timeout, and launches RadioInfo.
     */
    @Synchronized
    fun start5GLock(context: Context): Boolean {
        if (!checkAccessibilityEnabled(context)) {
            _automationStatus.value = AutomationStatus.Error("Accessibility Service is disabled. Please enable it first.")
            return false
        }

        switchStartTimeMs = System.currentTimeMillis()
        targetNetworkMode = TargetMode.NR_ONLY
        _automationStatus.value = AutomationStatus.Running(TargetMode.NR_ONLY, switchStartTimeMs)

        armTimeoutSafeguard("Switching to NR Only timed out after 4 seconds.")
        return launchRadioInfo(context)
    }

    /**
     * STOP 5G Lock (Restore Normal Mode) flow:
     * Sets target to NORMAL_AUTO, arms 4-second safeguard timeout, and launches RadioInfo.
     */
    @Synchronized
    fun stop5GLock(context: Context): Boolean {
        if (!checkAccessibilityEnabled(context)) {
            _automationStatus.value = AutomationStatus.Error("Accessibility Service is disabled. Please enable it first.")
            return false
        }

        switchStartTimeMs = System.currentTimeMillis()
        targetNetworkMode = TargetMode.NORMAL_AUTO
        _automationStatus.value = AutomationStatus.Running(TargetMode.NORMAL_AUTO, switchStartTimeMs)

        armTimeoutSafeguard("Restoring Normal Mode timed out after 4 seconds.")
        return launchRadioInfo(context)
    }

    /**
     * Arms the 4-second timeout safeguard to prevent looping or getting stuck.
     */
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

    /**
     * Called by FiveGAccessibilityService when the target network mode has been selected successfully.
     */
    @Synchronized
    fun onAutomationSuccess(context: Context, mode: TargetMode) {
        timeoutJob?.cancel()
        timeoutJob = null
        targetNetworkMode = null

        val elapsed = if (switchStartTimeMs > 0) System.currentTimeMillis() - switchStartTimeMs else 0L
        switchStartTimeMs = 0L

        // Persist new state
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_NETWORK_MODE, mode.name).apply()

        _currentNetworkState.value = when (mode) {
            TargetMode.NR_ONLY -> CurrentNetworkState.NR_ONLY_LOCKED
            TargetMode.NORMAL_AUTO -> CurrentNetworkState.NORMAL_AUTO
        }
        _automationStatus.value = AutomationStatus.Success(mode, elapsed)
        Log.i(TAG, "Automation completed successfully for $mode in ${elapsed}ms")
    }

    /**
     * Cancel / reset any ongoing automation request.
     */
    @Synchronized
    fun clearTargetMode() {
        timeoutJob?.cancel()
        timeoutJob = null
        targetNetworkMode = null
    }

    /**
     * Robust RadioInfo launcher supporting Android 10–14 (API 29–34).
     */
    fun launchRadioInfo(context: Context): Boolean {
        val candidates = listOf(
            ComponentName("com.android.phone", "com.android.phone.settings.RadioInfo"),
            ComponentName("com.android.settings", "com.android.settings.RadioInfo"),
            ComponentName("com.android.settings", "com.android.settings.Settings\$RadioInfoActivity"),
            ComponentName("com.android.settings", "com.android.settings.TestingSettings"),
            ComponentName("com.android.phone", "com.android.phone.RadioInfo")
        )

        for (component in candidates) {
            try {
                val intent = Intent(Intent.ACTION_MAIN).apply {
                    component = component
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_CLEAR_TOP or
                            Intent.FLAG_ACTIVITY_NO_ANIMATION
                }
                context.startActivity(intent)
                Log.d(TAG, "Successfully launched RadioInfo via $component")
                return true
            } catch (e: Exception) {
                Log.d(TAG, "Component $component not available: ${e.message}")
            }
        }

        // Fallback: Try secret code dialer broadcast/intent
        try {
            val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:*#*#4636#*#*")).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION
            }
            context.startActivity(intent)
            Log.d(TAG, "Fallback to dialer code launched.")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "All RadioInfo launch mechanisms failed: ${e.message}", e)
            _automationStatus.value = AutomationStatus.Error("Could not launch RadioInfo on this device.")
            clearTargetMode()
            return false
        }
    }
}
