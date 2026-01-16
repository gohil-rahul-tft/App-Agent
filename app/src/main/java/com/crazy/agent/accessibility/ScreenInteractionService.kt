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

        val nodeId = node.viewIdResourceName ?: ""

        val matches =
                if (exactMatch) {
                    nodeText.trim().equals(text.trim(), ignoreCase = true) ||
                            contentDesc.trim().equals(text.trim(), ignoreCase = true) ||
                            nodeId.trim().equals(text.trim(), ignoreCase = true)
                } else {
                    nodeText.contains(text, ignoreCase = true) ||
                            contentDesc.contains(text, ignoreCase = true) ||
                            nodeId.contains(text, ignoreCase = true)
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
            val nodeId = node.viewIdResourceName ?: ""

            if (nodeText.contains(hintText, ignoreCase = true) ||
                            contentDesc.contains(hintText, ignoreCase = true) ||
                            hint.contains(hintText, ignoreCase = true) ||
                            nodeId.contains(hintText, ignoreCase = true)
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
        val scrollableNode = findScrollableNode(rootNode)
        if (scrollableNode != null) {
            val result = scrollableNode.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            Timber.d("Scroll down result: $result on node: ${scrollableNode.className}")
            return result
        }
        Timber.w("No scrollable node found")
        return false
    }

    /** Scroll up */
    fun scrollUp(): Boolean {
        val rootNode = rootInActiveWindow ?: return false
        val scrollableNode = findScrollableNode(rootNode)
        if (scrollableNode != null) {
            val result = scrollableNode.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
            Timber.d("Scroll up result: $result on node: ${scrollableNode.className}")
            return result
        }
        Timber.w("No scrollable node found")
        return false
    }
    
    private fun findScrollableNode(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        // Prefer RecyclerView/ListView
        if (node.isScrollable) {
            val className = node.className?.toString() ?: ""
            if (className.contains("RecyclerView") || 
                className.contains("ListView") || 
                className.contains("ScrollView")) {
                return node
            }
        }
        
        // Search children
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val result = findScrollableNode(child)
            if (result != null) return result
        }
        
        // Fallback: return any scrollable node
        if (node.isScrollable) return node
        
        return null
    }

    /** Find a list item by its index */
    fun findListItemByIndex(elementType: String, index: Int): AccessibilityNodeInfo? {
        val rootNode = rootInActiveWindow ?: return null
        return findListItemByIndexRecursive(rootNode, elementType, index)
    }

    private fun findListItemByIndexRecursive(
            node: AccessibilityNodeInfo,
            elementType: String,
            targetIndex: Int
    ): AccessibilityNodeInfo? {
        // Check if this is a list container
        val className = node.className?.toString() ?: ""
        if (className.contains("RecyclerView") || className.contains("ListView") || className.contains("GridView")) {
            // Found a list, now find the child at the target index
            var currentIndex = 0
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                if (!isVisible(child)) continue
                
                // Check if this child matches the element type
                if (matchesElementType(child, elementType)) {
                    if (currentIndex == targetIndex) {
                        return child
                    }
                    currentIndex++
                }
            }
        }

        // Recursively search children
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val result = findListItemByIndexRecursive(child, elementType, targetIndex)
            if (result != null) {
                return result
            }
        }

        return null
    }

    private fun matchesElementType(node: AccessibilityNodeInfo, elementType: String): Boolean {
        val desc = node.contentDescription?.toString() ?: ""
        val id = node.viewIdResourceName ?: ""
        
        return when (elementType) {
            "video" -> desc.contains("minute") && desc.contains("second")
            "button" -> node.className?.toString()?.contains("Button") == true
            "input", "search_field" -> node.isEditable
            else -> true // Match any if type is not specific
        }
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

    private fun dumpNodeRecursive(node: AccessibilityNodeInfo, sb: StringBuilder, depth: Int = 0, indexInParent: Int = -1, parentIsListContainer: Boolean = false) {
        // Check if THIS node is a list container
        val isThisNodeListContainer = isListContainer(node)
        
        // Only include interesting nodes to save tokens
        if (!isInterestingNode(node)) {
            // Still traverse children of uninteresting nodes
            // Pass down the list container flag if this node is a list
            val passDownListFlag = isThisNodeListContainer || parentIsListContainer
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                dumpNodeRecursive(child, sb, depth, i, passDownListFlag)
            }
            return
        }
        
        if (sb.length > 1 && sb[sb.length - 1] != '[' && sb[sb.length - 1] != ',') {
            sb.append(",")
        }
        
        sb.append("{")
        
        // Detect element type for common patterns
        val elementType = detectElementType(node)
        if (elementType != null) {
            sb.append("\"type\":\"$elementType\",")
        }
        
        // Add index if we're inside a list container (even if nested)
        // Only add index to "interesting" nodes that are direct or nested children of list
        if (indexInParent >= 0 && parentIsListContainer && !isThisNodeListContainer) {
            sb.append("\"index\":$indexInParent,")
        }
        
        // Text/Desc (prioritize these as they're most useful)
        val text = node.text?.toString()?.ellipsize(80)
        if (!text.isNullOrBlank()) sb.append("\"text\":\"$text\",")
        
        val desc = node.contentDescription?.toString()?.ellipsize(80)
        if (!desc.isNullOrBlank()) sb.append("\"desc\":\"$desc\",")
        
        // Resource ID (useful for targeting)
        val viewId = node.viewIdResourceName
        if (viewId != null) {
            val shortId = viewId.substringAfterLast("/")
            sb.append("\"id\":\"$shortId\",")
        }
        
        // Simplified position instead of exact bounds
        val bounds = android.graphics.Rect()
        node.getBoundsInScreen(bounds)
        val screenHeight = 2280 // Approximate, could get from resources
        val position = when {
            bounds.top < screenHeight / 3 -> "top"
            bounds.top < 2 * screenHeight / 3 -> "mid"
            else -> "bot"
        }
        sb.append("\"pos\":\"$position\",")
        
        // Actions (only if true)
        if (node.isClickable) sb.append("\"click\":true,")
        if (node.isScrollable) sb.append("\"scroll\":true,")
        if (node.isEditable) sb.append("\"edit\":true,")
        
        // Children (recursively, but skip wrapper nodes)
        val interestingChildren = mutableListOf<AccessibilityNodeInfo>()
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            if (isVisible(child)) {
                interestingChildren.add(child)
            }
        }
        
        if (interestingChildren.isNotEmpty()) {
            sb.append("\"children\":[")
            // If THIS node is a list container, its children should get indices
            // Otherwise, pass down the parent's list container flag
            val passDownListFlag = if (isThisNodeListContainer) true else parentIsListContainer
            interestingChildren.forEachIndexed { idx, child ->
                dumpNodeRecursive(child, sb, depth + 1, idx, passDownListFlag)
            }
            sb.append("]")
        } else {
            // Remove trailing comma if no children
            if (sb.endsWith(",")) sb.setLength(sb.length - 1)
        }
        
        // Close object
        if (sb.endsWith(",")) sb.setLength(sb.length - 1)
        sb.append("}")
    }
    
    private fun isListContainer(node: AccessibilityNodeInfo): Boolean {
        val className = node.className?.toString() ?: ""
        return className.contains("RecyclerView") || 
               className.contains("ListView") ||
               className.contains("GridView")
    }
    
    private fun isInterestingNode(node: AccessibilityNodeInfo): Boolean {
        // A node is interesting if it has meaningful content or actions
        val hasText = !node.text.isNullOrBlank()
        val hasDesc = !node.contentDescription.isNullOrBlank()
        val hasId = node.viewIdResourceName != null
        val isInteractive = node.isClickable || node.isEditable || node.isScrollable
        val isContainer = node.className?.toString()?.contains("RecyclerView") == true ||
                         node.className?.toString()?.contains("ListView") == true
        
        return hasText || hasDesc || hasId || isInteractive || isContainer
    }
    
    private fun isListItem(node: AccessibilityNodeInfo): Boolean {
        // Check if parent is a RecyclerView or ListView
        val parent = node.parent ?: return false
        val parentClass = parent.className?.toString() ?: ""
        return parentClass.contains("RecyclerView") || 
               parentClass.contains("ListView") ||
               parentClass.contains("GridView")
    }
    
    private fun detectElementType(node: AccessibilityNodeInfo): String? {
        val className = node.className?.toString() ?: return null
        val id = node.viewIdResourceName ?: ""
        val desc = node.contentDescription?.toString() ?: ""
        val text = node.text?.toString() ?: ""
        
        return when {
            // Video/media items
            desc.contains("minute") && desc.contains("second") -> "video"
            id.contains("thumbnail") -> "video"
            
            // Search fields
            id.contains("search") && node.isEditable -> "search_field"
            text.contains("Search", ignoreCase = true) && node.isEditable -> "search_field"
            
            // Buttons
            className.contains("Button") -> "button"
            
            // Text input
            node.isEditable -> "input"
            
            // Lists
            className.contains("RecyclerView") -> "list"
            className.contains("ListView") -> "list"
            
            else -> null
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
