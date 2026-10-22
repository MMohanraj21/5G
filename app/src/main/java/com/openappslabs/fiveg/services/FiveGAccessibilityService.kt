package com.openappslabs.fiveg.services

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.openappslabs.fiveg.automation.AutomationController
import com.openappslabs.fiveg.automation.TargetMode

/**
 * High-speed 1-Second Automated Switcher Accessibility Service.
 * Listens exclusively to com.android.phone and com.android.settings.
 */
class FiveGAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "FiveGAccessibility"
        private const val SPINNER_ID_PHONE = "com.android.phone:id/preferredNetworkType"
        private const val SPINNER_ID_SETTINGS = "com.android.settings:id/preferredNetworkType"
        private const val BACK_ACTION_DELAY_MS = 60L
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    private enum class AutomationStage {
        IDLE,
        CLICKED_SPINNER,
        COMPLETED
    }

    @Volatile
    private var stage = AutomationStage.IDLE
    private var lastSpinnerClickTime = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.i(TAG, "FiveGAccessibilityService connected.")

        // Configure service parameters dynamically to guarantee strict filter & 50ms responsiveness
        serviceInfo = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                    AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
            packageNames = arrayOf("com.android.phone", "com.android.settings")
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                    AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
            notificationTimeout = 50
        }

        AutomationController.checkAccessibilityEnabled(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val target = AutomationController.targetNetworkMode ?: run {
            stage = AutomationStage.IDLE
            return
        }

        val pkgName = event?.packageName?.toString() ?: return
        val isAllowedPackage = pkgName == "com.android.phone" ||
                pkgName == "com.android.settings" ||
                pkgName.contains("phone") ||
                pkgName.contains("settings") ||
                pkgName.contains("vivo") ||
                pkgName.contains("bbk")
        if (!isAllowedPackage) {
            return
        }

        val rootNode = rootInActiveWindow ?: return

        try {
            // Priority check: Is a network selection popup/dialog already visible on screen?
            val optionNode = findTargetOptionNode(rootNode, target)
            if (optionNode != null) {
                Log.d(TAG, "Found target network option node for $target. Clicking...")
                if (performClick(optionNode)) {
                    finishAutomation(target)
                    return
                }
            }

            // If we are looking for the Spinner (or if we already clicked it but need to open dropdown)
            if (stage == AutomationStage.IDLE ||
                (stage == AutomationStage.CLICKED_SPINNER && (System.currentTimeMillis() - lastSpinnerClickTime > 1500L))
            ) {
                val spinner = findPreferredNetworkSpinner(rootNode)
                if (spinner != null) {
                    Log.d(TAG, "Found preferred network Spinner. Clicking to open dropdown...")
                    if (performClick(spinner)) {
                        stage = AutomationStage.CLICKED_SPINNER
                        lastSpinnerClickTime = System.currentTimeMillis()
                        return
                    }
                } else {
                    // Try to scroll the screen down if the spinner is below the fold
                    scrollContainerForward(rootNode)
                }
            }

            // If spinner was clicked and popup is open, but target item is below the fold in the list
            if (stage == AutomationStage.CLICKED_SPINNER) {
                // Try scrolling the popup list down to reveal target item
                if (scrollContainerForward(rootNode)) {
                    val scrolledOption = findTargetOptionNode(rootNode, target)
                    if (scrolledOption != null && performClick(scrolledOption)) {
                        finishAutomation(target)
                        return
                    }
                }
            }
        } finally {
            // Note: rootNode will be recycled by Android runtime
        }
    }

    /**
     * Finishes the automation cleanly:
     * 1. Notifies AutomationController with elapsed time.
     * 2. Clears target mode.
     * 3. Calls GLOBAL_ACTION_BACK to return user to the app within 1 second.
     */
    private fun finishAutomation(target: TargetMode) {
        stage = AutomationStage.COMPLETED
        AutomationController.onAutomationSuccess(applicationContext, target)

        // Small delay to allow radio/telephony selection to register before pressing Back
        mainHandler.postDelayed({
            performGlobalAction(GLOBAL_ACTION_BACK)
            stage = AutomationStage.IDLE
        }, BACK_ACTION_DELAY_MS)
    }

    /**
     * Locates the Preferred Network Type Spinner via ID, class name, or label heuristics.
     */
    private fun findPreferredNetworkSpinner(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        // 1. Direct view ID match on phone package
        var list = root.findAccessibilityNodeInfosByViewId(SPINNER_ID_PHONE)
        if (!list.isNullOrEmpty()) return list[0]

        // 2. Direct view ID match on settings package
        list = root.findAccessibilityNodeInfosByViewId(SPINNER_ID_SETTINGS)
        if (!list.isNullOrEmpty()) return list[0]

        // 3. Fallback: Search by "preferredNetworkType" unqualified ID
        list = root.findAccessibilityNodeInfosByViewId("preferredNetworkType")
        if (!list.isNullOrEmpty()) return list[0]

        // 4. Recursive inspection: Match Spinner class or text patterns
        return searchSpinnerRecursive(root)
    }

    private fun searchSpinnerRecursive(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val className = node.className?.toString() ?: ""
        if (className.contains("Spinner", ignoreCase = true)) {
            return node
        }

        val text = node.text?.toString() ?: ""
        val contentDesc = node.contentDescription?.toString() ?: ""
        val combined = "$text $contentDesc"

        // Common network mode keywords in RadioInfo spinner
        if (combined.contains("NR", ignoreCase = true) ||
            combined.contains("LTE", ignoreCase = true) ||
            combined.contains("GSM", ignoreCase = true) ||
            combined.contains("WCDMA", ignoreCase = true) ||
            combined.contains("TDSCDMA", ignoreCase = true)
        ) {
            if (node.isClickable || node.parent?.isClickable == true) {
                return node
            }
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val result = searchSpinnerRecursive(child)
            if (result != null) return result
        }
        return null
    }

    /**
     * Locates the target node inside the opened network types list.
     */
    private fun findTargetOptionNode(
        root: AccessibilityNodeInfo,
        target: TargetMode
    ): AccessibilityNodeInfo? {
        val allVisibleNodes = mutableListOf<AccessibilityNodeInfo>()
        collectAllTextNodes(root, allVisibleNodes)

        return when (target) {
            TargetMode.NR_ONLY -> {
                // Exact match: "NR only" (case-insensitive, trimmed)
                allVisibleNodes.firstOrNull { node ->
                    val text = node.text?.toString()?.trim() ?: ""
                    text.equals("NR only", ignoreCase = true) ||
                            text.equals("NR Only", ignoreCase = true) ||
                            text.equals("NR_ONLY", ignoreCase = true) ||
                            text.equals("NR ONLY", ignoreCase = true)
                }
            }

            TargetMode.NORMAL_AUTO -> {
                // Check in user-specified priority order:
                // 1. "NR/LTE/TDSCDMA/CDMA/EvDo/GSM/WCDMA"
                // 2. "NR/LTE/GSM/WCDMA"
                // 3. "NR/LTE"
                for (preferredHybrid in AutomationController.NORMAL_AUTO_PRIORITY_LIST) {
                    val normalizedTarget = normalizeModeString(preferredHybrid)
                    val match = allVisibleNodes.firstOrNull { node ->
                        val nodeText = normalizeModeString(node.text?.toString() ?: "")
                        nodeText == normalizedTarget || nodeText.startsWith(normalizedTarget)
                    }
                    if (match != null) return match
                }
                null
            }
        }
    }

    /**
     * Normalizes string for tolerant comparison (removes spaces around slashes, lowercases).
     */
    private fun normalizeModeString(str: String): String {
        return str.replace(" ", "")
            .replace("-", "")
            .lowercase()
    }

    /**
     * Collects all nodes containing text in the active window hierarchy.
     */
    private fun collectAllTextNodes(
        node: AccessibilityNodeInfo,
        outList: MutableList<AccessibilityNodeInfo>
    ) {
        if (!node.text.isNullOrEmpty() || !node.contentDescription.isNullOrEmpty()) {
            outList.add(node)
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectAllTextNodes(child, outList)
        }
    }

    /**
     * Performs click on the node or walks up to the nearest clickable parent.
     */
    private fun performClick(node: AccessibilityNodeInfo): Boolean {
        var current: AccessibilityNodeInfo? = node
        while (current != null) {
            if (current.isClickable) {
                return current.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
            current = current.parent
        }
        return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    /**
     * Attempts to scroll forward on any scrollable container (e.g. ScrollView, ListView).
     */
    private fun scrollContainerForward(node: AccessibilityNodeInfo): Boolean {
        if (node.isScrollable) {
            return node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            if (scrollContainerForward(child)) return true
        }
        return false
    }

    override fun onInterrupt() {
        Log.w(TAG, "FiveGAccessibilityService interrupted.")
        stage = AutomationStage.IDLE
    }

    override fun onDestroy() {
        super.onDestroy()
        stage = AutomationStage.IDLE
        AutomationController.clearTargetMode()
        Log.i(TAG, "FiveGAccessibilityService destroyed.")
    }
}
