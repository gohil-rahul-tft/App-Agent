package com.crazy.agent.action

import android.content.Context
import android.content.Intent
import com.crazy.agent.accessibility.ScreenInteractionService
import com.crazy.agent.agent.ActionType
import com.crazy.agent.agent.AgentStep
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.delay
import timber.log.Timber
import kotlin.coroutines.resume

/** Executes agent steps using the accessibility service */
@Singleton
class ActionExecutor @Inject constructor(@ApplicationContext private val context: Context) {

    /** Capture current screen state (Screenshot + UI Tree) */
    suspend fun captureState(): Pair<android.graphics.Bitmap?, String> {
        val service = ScreenInteractionService.getInstance()
        if (service == null) {
            Timber.e("Service not connected")
            return null to "[]"
        }

        val tree = service.dumpWindowHierarchy()

        return kotlinx.coroutines.suspendCancellableCoroutine { cont ->
            service.captureScreen(java.util.concurrent.Executors.newSingleThreadExecutor()) { bitmap ->
                 if (cont.isActive) {
                     cont.resume(bitmap to tree)
                 }
            }
        }
    }

    /** Execute a single step */
    suspend fun executeStep(step: AgentStep): ActionResult {
        val service =
                ScreenInteractionService.getInstance()
                        ?: return ActionResult.Failure("Accessibility service not enabled")

        return try {
            when (step.action) {
                ActionType.OPEN_APP -> openApp(step.target)
                ActionType.FIND_ELEMENT -> findElement(service, step.target)
                ActionType.CLICK -> clickElement(service, step.target)
                ActionType.CLICK_INDEX -> clickByIndex(service, step.target, step.value?.toIntOrNull() ?: 0)
                ActionType.TYPE_TEXT -> typeText(service, step.target, step.value ?: "")
                ActionType.PRESS_ENTER -> pressEnter(service, step.target)
                ActionType.SEARCH -> search(service, step.value ?: "")
                ActionType.WAIT -> wait(step.target.toLongOrNull() ?: 1000)
                ActionType.NAVIGATE_BACK -> navigateBack(service)
                ActionType.SCROLL_DOWN -> scrollDown(service)
                ActionType.SCROLL_UP -> scrollUp(service)
                ActionType.TASK_COMPLETE -> ActionResult.Success("Task Complete")
            }
        } catch (e: Exception) {
            Timber.e(e, "Error executing step: ${step.description}")
            ActionResult.Failure("Error: ${e.message}")
        }
    }

    /** Open an app by package name */
    private suspend fun openApp(packageName: String): ActionResult {
        return try {
            val intent = context.packageManager.getLaunchIntentForPackage(packageName)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                ActionResult.Success("Opened app")
            } else {
                ActionResult.Failure("App not installed: $packageName")
            }
        } catch (e: Exception) {
            ActionResult.Failure("Failed to open app: ${e.message}")
        }
    }

    /** Find an element on screen */
    private suspend fun findElement(service: ScreenInteractionService, text: String): ActionResult {
        // Try to find the element with retries
        repeat(5) { attempt ->
            val node = service.findNodeByText(text, exactMatch = false)
            if (node != null) {
                return ActionResult.Success("Found element: $text")
            }

            if (attempt < 4) {
                delay(500)
            }
        }

        return ActionResult.Failure("Element not found: $text")
    }

    /** Click on an element */
    private suspend fun clickElement(
            service: ScreenInteractionService,
            text: String
    ): ActionResult {
        // Try to find and click the element
        repeat(5) { attempt ->
            val node = service.findNodeByText(text, exactMatch = false)
            if (node != null) {
                val clicked = service.clickNode(node)
                return if (clicked) {
                    ActionResult.Success("Clicked: $text")
                } else {
                    ActionResult.Failure("Failed to click: $text")
                }
            }

            if (attempt < 4) {
                delay(500)
            }
        }

        return ActionResult.Failure("Element not found to click: $text")
    }

    /** Type text into an input field */
    private suspend fun typeText(
            service: ScreenInteractionService,
            fieldHint: String,
            text: String
    ): ActionResult {
        // Try to find editable field
        repeat(5) { attempt ->
            val node = service.findEditableNode(fieldHint)
            if (node != null) {
                val typed = service.typeText(node, text)
                return if (typed) {
                    ActionResult.Success("Typed text: $text")
                } else {
                    ActionResult.Failure("Failed to type text")
                }
            }

            if (attempt < 4) {
                delay(500)
            }
        }

        return ActionResult.Failure("Input field not found: $fieldHint")
    }

    /** Perform a search */
    private suspend fun search(service: ScreenInteractionService, query: String): ActionResult {
        // Find search field and type query
        val searchField =
                service.findEditableNode("search")
                        ?: return ActionResult.Failure("Search field not found")

        val typed = service.typeText(searchField, query)
        return if (typed) {
            ActionResult.Success("Searched for: $query")
        } else {
            ActionResult.Failure("Failed to search")
        }
    }

    /** Press Enter on an input field */
    private suspend fun pressEnter(
            service: ScreenInteractionService,
            fieldHint: String
    ): ActionResult {
        // Try to find the editable field
        repeat(5) { attempt ->
            val node = service.findEditableNode(fieldHint)
            if (node != null) {
                val pressed = service.pressEnter(node)
                return if (pressed) {
                    ActionResult.Success("Pressed Enter")
                } else {
                    ActionResult.Failure("Failed to press Enter")
                }
            }

            if (attempt < 4) {
                delay(500)
            }
        }

        return ActionResult.Failure("Input field not found: $fieldHint")
    }

    /** Wait for specified duration */
    private suspend fun wait(milliseconds: Long): ActionResult {
        delay(milliseconds)
        return ActionResult.Success("Waited ${milliseconds}ms")
    }

    /** Navigate back */
    private suspend fun navigateBack(service: ScreenInteractionService): ActionResult {
        val success = service.pressBack()
        return if (success) {
            ActionResult.Success("Navigated back")
        } else {
            ActionResult.Failure("Failed to navigate back")
        }
    }

    /** Scroll down */
    private suspend fun scrollDown(service: ScreenInteractionService): ActionResult {
        val success = service.scrollDown()
        return if (success) {
            ActionResult.Success("Scrolled down")
        } else {
            ActionResult.Failure("Failed to scroll down (no scrollable container found?)")
        }
    }

    /** Scroll up */
    private suspend fun scrollUp(service: ScreenInteractionService): ActionResult {
        val success = service.scrollUp()
        return if (success) {
            ActionResult.Success("Scrolled up")
        } else {
            ActionResult.Failure("Failed to scroll up")
        }
    }

    /** Click an element by its index in a list */
    private suspend fun clickByIndex(
            service: ScreenInteractionService,
            elementType: String,
            index: Int
    ): ActionResult {
        // Try to find the list item by index
        repeat(5) { attempt ->
            val node = service.findListItemByIndex(elementType, index)
            if (node != null) {
                val clicked = service.clickNode(node)
                return if (clicked) {
                    ActionResult.Success("Clicked item at index $index")
                } else {
                    ActionResult.Failure("Failed to click item at index $index")
                }
            }

            if (attempt < 4) {
                delay(500)
            }
        }

        return ActionResult.Failure("Element at index $index not found")
    }

    /** Execute a list of steps sequentially */
    suspend fun executePlan(
            steps: List<AgentStep>,
            onStepComplete: (Int, AgentStep, ActionResult) -> Unit
    ): ActionResult {
        steps.forEachIndexed { index, step ->
            val result = executeStep(step)
            onStepComplete(index, step, result)

            // If step failed, stop execution
            if (result is ActionResult.Failure) {
                return result
            }
        }

        return ActionResult.Success("All steps completed successfully")
    }
}
