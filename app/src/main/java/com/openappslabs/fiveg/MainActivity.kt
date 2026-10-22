package com.openappslabs.fiveg

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.openappslabs.fiveg.automation.AutomationController
import com.openappslabs.fiveg.automation.AutomationStatus
import com.openappslabs.fiveg.automation.CurrentNetworkState
import com.openappslabs.fiveg.automation.EngineMode
import com.openappslabs.fiveg.automation.ShizukuController
import com.openappslabs.fiveg.automation.TargetMode
import com.openappslabs.fiveg.ui.theme.FiveGTheme
import com.openappslabs.fiveg.ui.theme.Green5G
import com.openappslabs.fiveg.ui.theme.Green5GContainer
import com.openappslabs.fiveg.ui.theme.Green5GOnContainer

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        AutomationController.init(this)

        setContent {
            FiveGTheme {
                MainScreen()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen() {
    val context = LocalContext.current
    val activity = context as? Activity
    val lifecycleOwner = LocalLifecycleOwner.current

    val currentMode by AutomationController.currentNetworkState.collectAsState()
    val automationStatus by AutomationController.automationStatus.collectAsState()
    val selectedEngine by AutomationController.selectedEngine.collectAsState()
    val isAccessibilityEnabled by AutomationController.isAccessibilityEnabled.collectAsState()
    val shizukuState by ShizukuController.state.collectAsState()

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                AutomationController.checkAccessibilityEnabled(context)
                ShizukuController.checkState(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    val isRunning = automationStatus is AutomationStatus.Running

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "5G NR Switcher",
                            style = MaterialTheme.typography.titleLarge.copy(
                                fontWeight = FontWeight.ExtraBold
                            )
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.primaryContainer,
                            modifier = Modifier.padding(top = 2.dp)
                        ) {
                            Text(
                                text = "v2.1.0",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                ),
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 18.dp, vertical = 8.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {

            // 1. Current Automation Status Card
            StatusDisplayCard(
                networkState = currentMode,
                automationStatus = automationStatus
            )

            // 2. Specific Engine Selector (User Choice: Auto / Shizuku / Accessibility)
            SpecificEngineSelectorCard(
                selectedEngine = selectedEngine,
                shizukuState = shizukuState,
                isAccessibilityEnabled = isAccessibilityEnabled,
                onEngineSelected = { engine ->
                    AutomationController.setEngineMode(context, engine)
                },
                onRequestShizukuPermission = {
                    activity?.let { ShizukuController.requestPermission(it) }
                },
                onOpenAccessibilitySettings = {
                    val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                }
            )

            // 3. Prominent Start / Stop Controls
            ControlPanel(
                isRunning = isRunning,
                onStartClick = {
                    AutomationController.start5GLock(context)
                },
                onStopClick = {
                    AutomationController.stop5GLock(context)
                }
            )

            // 4. Quick Settings Tile Tip Card
            QuickSettingsTipCard(selectedEngine = selectedEngine)

            // 5. Diagnostic Manual Fallback
            OutlinedButton(
                onClick = {
                    AutomationController.launchRadioInfo(context)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 2.dp),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Info,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Open RadioInfo Manually",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
}

/**
 * Card providing explicit, specific choice of the switching engine.
 */
@Composable
fun SpecificEngineSelectorCard(
    selectedEngine: EngineMode,
    shizukuState: com.openappslabs.fiveg.automation.ShizukuState,
    isAccessibilityEnabled: Boolean,
    onEngineSelected: (EngineMode) -> Unit,
    onRequestShizukuPermission: () -> Unit,
    onOpenAccessibilitySettings: () -> Unit
) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Settings,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "SPECIFIC SWITCHING ENGINE",
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp,
                        color = MaterialTheme.colorScheme.primary
                    )
                )
            }

            // Engine Option 1: AUTO (Smart Fallback)
            EngineOptionItem(
                title = "Auto (Smart Fallback)",
                subtitle = "Uses Shizuku (silent 50ms) if active, else Accessibility (1-sec UI)",
                badgeText = "Recommended",
                badgeColor = MaterialTheme.colorScheme.primary,
                isSelected = selectedEngine == EngineMode.AUTO,
                onClick = { onEngineSelected(EngineMode.AUTO) }
            )

            // Engine Option 2: SHIZUKU ONLY (Pure Silent Background)
            EngineOptionItem(
                title = "Shizuku Engine (Pure Silent)",
                subtitle = "Invisible 50ms background switch. Never opens RadioInfo or flashes screen.",
                badgeText = if (shizukuState.isRunning && shizukuState.isPermissionGranted) "Active 🟢" else "Needs Setup 🟡",
                badgeColor = if (shizukuState.isRunning && shizukuState.isPermissionGranted) Green5G else Color(0xFFEAB308),
                isSelected = selectedEngine == EngineMode.SHIZUKU_ONLY,
                onClick = { onEngineSelected(EngineMode.SHIZUKU_ONLY) }
            )

            // Engine Option 3: ACCESSIBILITY ONLY (UI Automation)
            EngineOptionItem(
                title = "Accessibility Engine (UI Auto)",
                subtitle = "Automated 1-second RadioInfo screen click. No Shizuku/ADB needed.",
                badgeText = if (isAccessibilityEnabled) "Ready 🟢" else "Disabled 🔴",
                badgeColor = if (isAccessibilityEnabled) Green5G else MaterialTheme.colorScheme.error,
                isSelected = selectedEngine == EngineMode.A11Y_ONLY,
                onClick = { onEngineSelected(EngineMode.A11Y_ONLY) }
            )

            // Dynamic Action / Setup Button based on selected engine
            when (selectedEngine) {
                EngineMode.SHIZUKU_ONLY, EngineMode.AUTO -> {
                    if (shizukuState.isRunning && !shizukuState.isPermissionGranted) {
                        Button(
                            onClick = onRequestShizukuPermission,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary
                            )
                        ) {
                            Icon(Icons.Default.Bolt, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Grant Shizuku Permission")
                        }
                    } else if (!shizukuState.isRunning && selectedEngine == EngineMode.SHIZUKU_ONLY) {
                        Text(
                            text = "⚠️ Shizuku service is not running. Please open the Shizuku app and start it via Wireless Debugging, or select Auto mode.",
                            style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.error)
                        )
                    }
                }

                EngineMode.A11Y_ONLY -> {
                    if (!isAccessibilityEnabled) {
                        Button(
                            onClick = onOpenAccessibilitySettings,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.error
                            )
                        ) {
                            Text("Enable Auto-Switch Accessibility Service")
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun EngineOptionItem(
    title: String,
    subtitle: String,
    badgeText: String,
    badgeColor: Color,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = if (isSelected) MaterialTheme.colorScheme.surface else Color.Transparent,
        border = if (isSelected) androidx.compose.foundation.BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary) else null,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RadioButton(
                selected = isSelected,
                onClick = onClick,
                modifier = Modifier.size(20.dp)
            )

            Spacer(modifier = Modifier.width(12.dp))

            Column(
                modifier = Modifier.weight(1f)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontWeight = FontWeight.Bold
                        )
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = badgeColor.copy(alpha = 0.15f)
                    ) {
                        Text(
                            text = badgeText,
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = FontWeight.Bold,
                                color = badgeColor
                            ),
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall.copy(
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                    )
                )
            }
        }
    }
}

/**
 * Status Card displaying the active network automation state and engine feedback.
 */
@Composable
fun StatusDisplayCard(
    networkState: CurrentNetworkState,
    automationStatus: AutomationStatus
) {
    val isLocked = networkState == CurrentNetworkState.NR_ONLY_LOCKED
    val isAuto = networkState == CurrentNetworkState.NORMAL_AUTO

    val containerColor by animateColorAsState(
        targetValue = when {
            isLocked -> Green5GContainer
            isAuto -> MaterialTheme.colorScheme.secondaryContainer
            else -> MaterialTheme.colorScheme.surfaceVariant
        },
        label = "statusContainer"
    )

    val contentColor by animateColorAsState(
        targetValue = when {
            isLocked -> Green5GOnContainer
            isAuto -> MaterialTheme.colorScheme.onSecondaryContainer
            else -> MaterialTheme.colorScheme.onSurfaceVariant
        },
        label = "statusContent"
    )

    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.elevatedCardColors(containerColor = containerColor),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = "CURRENT NETWORK MODE",
                style = MaterialTheme.typography.labelSmall.copy(
                    letterSpacing = 1.5.sp,
                    fontWeight = FontWeight.Bold,
                    color = contentColor.copy(alpha = 0.7f)
                )
            )

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .clip(CircleShape)
                        .background(contentColor.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (isLocked) Icons.Default.Lock else Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = contentColor,
                        modifier = Modifier.size(24.dp)
                    )
                }

                Spacer(modifier = Modifier.width(10.dp))

                Text(
                    text = when (networkState) {
                        CurrentNetworkState.NR_ONLY_LOCKED -> "5G NR Locked"
                        CurrentNetworkState.NORMAL_AUTO -> "Normal Auto Mode (NR/LTE)"
                        CurrentNetworkState.UNKNOWN -> "Status Not Set"
                    },
                    style = MaterialTheme.typography.headlineSmall.copy(
                        fontWeight = FontWeight.ExtraBold,
                        color = contentColor
                    )
                )
            }

            Text(
                text = when (networkState) {
                    CurrentNetworkState.NR_ONLY_LOCKED ->
                        "Connected strictly to 5G Standalone (NR Only). LTE fallback disabled."
                    CurrentNetworkState.NORMAL_AUTO ->
                        "Standard hybrid auto mode (NR/LTE/GSM). Allows seamless cellular calling & roaming."
                    CurrentNetworkState.UNKNOWN ->
                        "Tap 'Start 5G Lock' or 'Stop' below to switch network modes."
                },
                style = MaterialTheme.typography.bodyMedium.copy(color = contentColor),
                textAlign = TextAlign.Center
            )

            when (automationStatus) {
                is AutomationStatus.Running -> {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp)
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = contentColor
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Switching via ${automationStatus.engineName}...",
                            style = MaterialTheme.typography.labelMedium.copy(
                                fontWeight = FontWeight.Bold,
                                color = contentColor
                            )
                        )
                    }
                }

                is AutomationStatus.Success -> {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = contentColor.copy(alpha = 0.12f),
                        modifier = Modifier.padding(top = 2.dp)
                    ) {
                        Text(
                            text = "⚡ Switched to ${if (automationStatus.target == TargetMode.NR_ONLY) "NR Only" else "Normal Mode"} via ${automationStatus.engineName} in ${automationStatus.elapsedMs}ms",
                            style = MaterialTheme.typography.labelMedium.copy(
                                fontWeight = FontWeight.SemiBold,
                                color = contentColor
                            ),
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                        )
                    }
                }

                is AutomationStatus.Timeout -> {
                    Text(
                        text = "⚠️ ${automationStatus.message}",
                        style = MaterialTheme.typography.labelSmall.copy(
                            color = MaterialTheme.colorScheme.error,
                            fontWeight = FontWeight.Bold
                        )
                    )
                }

                is AutomationStatus.Error -> {
                    Text(
                        text = "❌ ${automationStatus.message}",
                        style = MaterialTheme.typography.labelSmall.copy(
                            color = MaterialTheme.colorScheme.error,
                            fontWeight = FontWeight.Bold
                        )
                    )
                }

                AutomationStatus.Idle -> Unit
            }
        }
    }
}

/**
 * Prominent Control Panel: Start & Stop buttons.
 */
@Composable
fun ControlPanel(
    isRunning: Boolean,
    onStartClick: () -> Unit,
    onStopClick: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = "AUTOMATION CONTROLS",
            style = MaterialTheme.typography.labelMedium.copy(
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            ),
            modifier = Modifier.padding(start = 4.dp)
        )

        // START 5G LOCK (NR ONLY)
        Button(
            onClick = onStartClick,
            enabled = !isRunning,
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp),
            shape = RoundedCornerShape(18.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary
            ),
            elevation = ButtonDefaults.buttonElevation(defaultElevation = 4.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Start,
                modifier = Modifier.fillMaxWidth()
            ) {
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.2f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Lock,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        text = "Start 5G Lock (NR Only)",
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Bold
                        )
                    )
                    Text(
                        text = "Direct lock to pure 5G Standalone",
                        style = MaterialTheme.typography.bodySmall.copy(
                            color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.8f)
                        )
                    )
                }
            }
        }

        // STOP (RESTORE NORMAL NR/LTE)
        FilledTonalButton(
            onClick = onStopClick,
            enabled = !isRunning,
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp),
            shape = RoundedCornerShape(18.dp),
            colors = ButtonDefaults.filledTonalButtonColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer
            )
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Start,
                modifier = Modifier.fillMaxWidth()
            ) {
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.LockOpen,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        text = "Stop (Restore Normal NR/LTE)",
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Bold
                        )
                    )
                    Text(
                        text = "Restores hybrid network mode for calling",
                        style = MaterialTheme.typography.bodySmall.copy(
                            color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f)
                        )
                    )
                }
            }
        }
    }
}

@Composable
fun QuickSettingsTipCard(selectedEngine: EngineMode) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Refresh,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(26.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text(
                    text = "1-Tap Quick Settings Tile",
                    style = MaterialTheme.typography.titleSmall.copy(
                        fontWeight = FontWeight.Bold
                    )
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = if (selectedEngine == EngineMode.SHIZUKU_ONLY || selectedEngine == EngineMode.AUTO)
                        "With Shizuku active, tapping the Quick Settings tile switches 5G completely silently in the background without opening the screen!"
                    else
                        "Swipe down your status bar and tap the '5G Switcher' tile for instant 1-tap switching anywhere.",
                    style = MaterialTheme.typography.bodySmall.copy(
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                )
            }
        }
    }
}
