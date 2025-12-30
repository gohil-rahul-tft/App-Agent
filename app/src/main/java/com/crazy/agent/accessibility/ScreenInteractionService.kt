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
                    nodeText.equals(text, ignoreCase = true) ||
                            contentDesc.equals(text, ignoreCase = true)
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
        // AccessibilityNodeInfo doesn't directly support IME actions like SEARCH/GO
        // But we can try to set the text with a newline character as a fallback
        // Or try to find a submit button nearby (simulated)

        // As a workaround, we'll try to focus and click the node if it's clickable
        // This sometimes triggers the default action
        if (node.isClickable) {
            return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
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
    fun getAllTextFromScreen(): List<String> {
        val rootNode = rootInActiveWindow ?: return emptyList()
        val texts = mutableListOf<String>()
        collectAllText(rootNode, texts)
        return texts
    }

    private fun collectAllText(node: AccessibilityNodeInfo, result: MutableList<String>) {
        node.text?.toString()?.let { if (it.isNotBlank()) result.add(it) }
        node.contentDescription?.toString()?.let { if (it.isNotBlank()) result.add(it) }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectAllText(child, result)
        }
    }
}
