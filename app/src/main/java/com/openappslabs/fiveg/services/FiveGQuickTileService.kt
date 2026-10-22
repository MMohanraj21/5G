package com.openappslabs.fiveg.services

import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.provider.Settings
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log
import android.widget.Toast
import com.openappslabs.fiveg.R
import com.openappslabs.fiveg.automation.AutomationController
import com.openappslabs.fiveg.automation.AutomationStatus
import com.openappslabs.fiveg.automation.CurrentNetworkState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * 1-Tap Toggle Quick Settings Tile for instant 5G NR Lock switching.
 */
class FiveGQuickTileService : TileService() {

    companion object {
        private const val TAG = "FiveGQuickTile"
    }

    private val serviceScope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())
    private var stateJob: Job? = null

    override fun onStartListening() {
        super.onStartListening()
        AutomationController.init(this)
        observeState()
    }

    override fun onStopListening() {
        super.onStopListening()
        stateJob?.cancel()
        stateJob = null
    }

    private fun observeState() {
        stateJob?.cancel()
        stateJob = serviceScope.launch {
            AutomationController.currentNetworkState.collectLatest { networkState ->
                updateTileUi(networkState, AutomationController.automationStatus.value)
            }
        }
    }

    private fun updateTileUi(networkState: CurrentNetworkState, status: AutomationStatus) {
        val tile = qsTile ?: return

        when (networkState) {
            CurrentNetworkState.NR_ONLY_LOCKED -> {
                tile.state = Tile.STATE_ACTIVE
                tile.label = getString(R.string.app_name)
                tile.subtitle = if (status is AutomationStatus.Running) "Switching..." else "5G NR Locked"
                tile.icon = Icon.createWithResource(this, R.drawable.ic_5g_lock)
            }

            CurrentNetworkState.NORMAL_AUTO -> {
                tile.state = Tile.STATE_INACTIVE
                tile.label = getString(R.string.app_name)
                tile.subtitle = if (status is AutomationStatus.Running) "Switching..." else "Normal (NR/LTE)"
                tile.icon = Icon.createWithResource(this, R.drawable.ic_5g)
            }

            CurrentNetworkState.UNKNOWN -> {
                tile.state = Tile.STATE_INACTIVE
                tile.label = getString(R.string.app_name)
                tile.subtitle = if (status is AutomationStatus.Running) "Switching..." else "Auto-Switcher"
                tile.icon = Icon.createWithResource(this, R.drawable.ic_5g)
            }
        }

        tile.updateTile()
    }

    override fun onClick() {
        super.onClick()

        // 1. Check if an automation engine is ready
        val shizukuReady = com.openappslabs.fiveg.automation.ShizukuController.checkState(this).let { it.isRunning && it.isPermissionGranted }
        val a11yReady = AutomationController.isAccessibilityServiceEnabled(this)
        val engine = AutomationController.selectedEngine.value

        val canAutomate = when (engine) {
            com.openappslabs.fiveg.automation.EngineMode.SHIZUKU_ONLY -> shizukuReady
            com.openappslabs.fiveg.automation.EngineMode.A11Y_ONLY -> a11yReady
            com.openappslabs.fiveg.automation.EngineMode.AUTO -> shizukuReady || a11yReady
        }

        if (!canAutomate) {
            Toast.makeText(
                this,
                "Please enable Shizuku or Accessibility Service",
                Toast.LENGTH_LONG
            ).show()

            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                val pendingIntent = PendingIntent.getActivity(
                    this,
                    0,
                    intent,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
                startActivityAndCollapse(pendingIntent)
            } else {
                @Suppress("DEPRECATION")
                startActivityAndCollapse(intent)
            }
            return
        }

        // 2. Perform 1-tap Toggle
        val currentState = AutomationController.currentNetworkState.value
        val tile = qsTile

        if (currentState == CurrentNetworkState.NR_ONLY_LOCKED) {
            Log.d(TAG, "Tile clicked: Restoring Normal Mode...")
            tile?.subtitle = "Restoring..."
            tile?.updateTile()
            AutomationController.stop5GLock(this)
        } else {
            Log.d(TAG, "Tile clicked: Locking 5G NR Only...")
            tile?.subtitle = "Locking NR..."
            tile?.updateTile()
            AutomationController.start5GLock(this)
        }
    }
}
