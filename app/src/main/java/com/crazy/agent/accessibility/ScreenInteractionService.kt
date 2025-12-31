package com.crazy.agent.accessibility

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import timber.log.Timber

/** Accessibility Service for interacting with UI elements in other apps */
class ScreenInteractionService : AccessibilityService() {

    companion object {
        @Volatile private var instance: ScreenInteractionService? = null

        fun getInstance(): ScreenInteractionService? = instance
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Timber.d("ScreenInteractionService connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // We don't need to handle events for this use case
        // We're using the service primarily for performing actions
    }

    override fun onInterrupt() {
        Timber.d("ScreenInteractionService interrupted")
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
    }

    /** Find a node by text (searches content description and text) */
    fun findNodeByText(text: String, exactMatch: Boolean = false): AccessibilityNodeInfo? {
        val rootNode = rootInActiveWindow ?: return null
        return findNodeByTextRecursive(rootNode, text, exactMatch)
    }

    private fun findNodeByTextRecursive(
            node: AccessibilityNodeInfo,
            text: String,
            exactMatch: Boolean
    ): AccessibilityNodeInfo? {
        // Check current node
        val nodeText = node.text?.toString() ?: ""
        val contentDesc = node.contentDescription?.toString() ?: ""

        val matches =
                if (exactMatch) {
                    nodeText.trim().equals(text.trim(), ignoreCase = true) ||
                            contentDesc.trim().equals(text.trim(), ignoreCase = true)
                } else {
                    nodeText.contains(text, ignoreCase = true) ||
                            contentDesc.contains(text, ignoreCase = true)
                }

        if (matches) {
            return node
        }

        // Search children
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val result = findNodeByTextRecursive(child, text, exactMatch)
            if (result != null) {
                return result
            }
        }

        return null
    }

    /** Find all clickable nodes (useful for debugging) */
    fun findClickableNodes(): List<AccessibilityNodeInfo> {
        val rootNode = rootInActiveWindow ?: return emptyList()
        val clickableNodes = mutableListOf<AccessibilityNodeInfo>()
        findClickableNodesRecursive(rootNode, clickableNodes)
        return clickableNodes
    }

    private fun findClickableNodesRecursive(
            node: AccessibilityNodeInfo,
            result: MutableList<AccessibilityNodeInfo>
    ) {
        if (node.isClickable) {
            result.add(node)
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            findClickableNodesRecursive(child, result)
        }
    }

    /** Find an editable text field */
    fun findEditableNode(hintText: String? = null): AccessibilityNodeInfo? {
        val rootNode = rootInActiveWindow ?: return null
        return findEditableNodeRecursive(rootNode, hintText)
    }

    private fun findEditableNodeRecursive(
            node: AccessibilityNodeInfo,
            hintText: String?
    ): AccessibilityNodeInfo? {
        if (node.isEditable) {
            if (hintText == null) {
                return node
            }

            val nodeText = node.text?.toString() ?: ""
            val contentDesc = node.contentDescription?.toString() ?: ""
            val hint = node.hintText?.toString() ?: ""

            if (nodeText.contains(hintText, ignoreCase = true) ||
                            contentDesc.contains(hintText, ignoreCase = true) ||
                            hint.contains(hintText, ignoreCase = true)
            ) {
                return node
            }
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val result = findEditableNodeRecursive(child, hintText)
            if (result != null) {
                return result
            }
        }

        return null
    }

    /** Click on a node */
    fun clickNode(node: AccessibilityNodeInfo): Boolean {
        return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    /** Type text into a node */
    fun typeText(node: AccessibilityNodeInfo, text: String): Boolean {
        // Focus on the node first
        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)

        // Set the text
        val arguments = android.os.Bundle()
        arguments.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
    }

    /** Press back button */
    fun pressBack(): Boolean {
        return performGlobalAction(GLOBAL_ACTION_BACK)
    }

    /** Go to home screen */
    fun pressHome(): Boolean {
        return performGlobalAction(GLOBAL_ACTION_HOME)
    }

    /** Press Enter/Submit on the current focused input */
    fun pressEnter(node: AccessibilityNodeInfo): Boolean {
        // 1. Try to click the node itself (sometimes triggers IME action)
        if (node.isClickable) {
            val clicked = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            if (clicked) return true
        }

        // 2. Try to find a "Submit" button nearby (Search, Send, Go, etc.)
        val commonSubmitTerms = listOf("Search", "Send", "Go", "Enter", "Submit", "Done")
        for (term in commonSubmitTerms) {
            val submitNode = findNodeByText(term, exactMatch = false)
            if (submitNode != null && submitNode.isClickable) {
                Timber.d("Found submit button using term: $term")
                return submitNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
        }
        
        // 3. Last resort: Try to click the parent if it's clickable
        var parent = node.parent
        while (parent != null) {
            if (parent.isClickable) {
                 return parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
            parent = parent.parent
        }

        return false
    }

    /** Scroll down */
    fun scrollDown(): Boolean {
        val rootNode = rootInActiveWindow ?: return false
        return rootNode.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
    }

    /** Scroll up */
    fun scrollUp(): Boolean {
        val rootNode = rootInActiveWindow ?: return false
        return rootNode.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
    }

    /** Get all text from screen (useful for debugging) */
    /*fun getAllTextFromScreen(): List<String> {
        val rootNode = rootInActiveWindow ?: return emptyList()
        val texts = mutableListOf<String>()
        collectAllText(rootNode, texts)
        return texts
    }*/

    /** Capture the current screen (Requires API 30+) */
    fun captureScreen(
            executor: java.util.concurrent.Executor,
            callback: (android.graphics.Bitmap?) -> Unit
    ) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            takeScreenshot(
                    android.view.Display.DEFAULT_DISPLAY,
                    executor,
                    object : TakeScreenshotCallback {
                        override fun onSuccess(screenshot: ScreenshotResult) {
                            val bitmap =
                                    android.graphics.Bitmap.wrapHardwareBuffer(
                                            screenshot.hardwareBuffer,
                                            screenshot.colorSpace
                                    )
                            // Copy to software bitmap for easier processing if needed, 
                            // or just pass the hardware bitmap. 
                            // Hardware bitmaps are immutable and strict about access.
                            // Let's copy it to be safe for Gemini processing.
                            val softwareBitmap = bitmap?.copy(android.graphics.Bitmap.Config.ARGB_8888, false)
                            screenshot.hardwareBuffer.close()
                            callback(softwareBitmap)
                        }

                        override fun onFailure(errorCode: Int) {
                            Timber.e("Screenshot failed with error code: $errorCode")
                            callback(null)
                        }
                    }
            )
        } else {
            Timber.e("Screen capture requires Android 11+ (API 30)")
            callback(null)
        }
    }

    /** Dump the current window hierarchy to a simplified JSON string */
    fun dumpWindowHierarchy(): String {
        val rootNode = rootInActiveWindow ?: return "[]"
        val sb = StringBuilder()
        sb.append("[")
        dumpNodeRecursive(rootNode, sb)
        sb.append("]")
        return sb.toString()
    }

    private fun dumpNodeRecursive(node: AccessibilityNodeInfo, sb: StringBuilder) {
        // Only include interesting nodes to save tokens
        if (isVisible(node)) {
            sb.append("{")
            
            // Basic Info
            sb.append("\"class\":\"${node.className}\",")
            
            // Text/Desc
            val text = node.text?.toString()?.ellipsize(50)
            if (!text.isNullOrBlank()) sb.append("\"text\":\"$text\",")
            
            val desc = node.contentDescription?.toString()?.ellipsize(50)
            if (!desc.isNullOrBlank()) sb.append("\"desc\":\"$desc\",")
            
            // IDs are tricky in AccessibilityNodeInfo, use viewIdResourceName if available
            val viewId = node.viewIdResourceName
            if (viewId != null) sb.append("\"id\":\"$viewId\",")

            // Bounds
            val bounds = android.graphics.Rect()
            node.getBoundsInScreen(bounds)
            sb.append("\"bounds\":\"${bounds.toShortString()}\",")

            // Actions
            if (node.isClickable) sb.append("\"clickable\":true,")
            if (node.isScrollable) sb.append("\"scrollable\":true,")
            if (node.isEditable) sb.append("\"editable\":true,")

            // Children
            if (node.childCount > 0) {
                sb.append("\"children\":[")
                var firstChild = true
                for (i in 0 until node.childCount) {
                    val child = node.getChild(i) ?: continue
                    if (isVisible(child)) { // Prune invisible branches early
                         if (!firstChild) sb.append(",")
                         dumpNodeRecursive(child, sb)
                         firstChild = false
                    }
                }
                sb.append("]")
            } else {
                 // Remove trailing comma if no children but properties added
                 if (sb.endsWith(",")) sb.setLength(sb.length - 1)
            }
            
            // Close object
            if (sb.endsWith(",")) sb.setLength(sb.length - 1)
            sb.append("}")
        }
    }

    private fun isVisible(node: AccessibilityNodeInfo): Boolean {
        // Basic visibility check
        if (!node.isVisibleToUser) return false
        
        val bounds = android.graphics.Rect()
        node.getBoundsInScreen(bounds)
        // Check if bounds are empty or off-screen (basic check)
        if (bounds.isEmpty) return false
        
        return true
    }

    private fun String.ellipsize(maxLength: Int): String {
        return if (this.length > maxLength) this.take(maxLength) + "..." else this.replace("\"", "\\\"")
    }
}
