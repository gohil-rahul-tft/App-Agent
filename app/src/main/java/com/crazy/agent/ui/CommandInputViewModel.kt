package com.crazy.agent.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.crazy.agent.action.ActionExecutor
import com.crazy.agent.action.ActionResult
import com.crazy.agent.agent.AgentPlanBuilder
import com.crazy.agent.agent.AgentStep
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber

/** ViewModel for command input and execution */
@HiltViewModel
class CommandInputViewModel
@Inject
constructor(private val planBuilder: AgentPlanBuilder, private val actionExecutor: ActionExecutor) :
        ViewModel() {

    private val _uiState = MutableStateFlow<CommandUiState>(CommandUiState.Idle)
    val uiState: StateFlow<CommandUiState> = _uiState.asStateFlow()

    private val _executedSteps = MutableStateFlow<List<StepExecution>>(emptyList())
    val executedSteps: StateFlow<List<StepExecution>> = _executedSteps.asStateFlow()

    /** Execute a natural language command using the Visual Loop */
    fun executeCommand(command: String) {
        if (command.isBlank()) return

        viewModelScope.launch {
            // Reset state
            _executedSteps.value = emptyList()
            _uiState.value = CommandUiState.Parsing // Show "Thinking" state
            
            val maxSteps = 15
            var stepCount = 0
            val history = StringBuilder()
            
            while (stepCount < maxSteps) {
                // 1. Observe: Capture State
                val (bitmap, uiTree) = actionExecutor.captureState()
                
                if (bitmap == null) {
                     _uiState.value = CommandUiState.Error("Failed to capture screen. is Accessibility Service enabled?")
                     return@launch
                }

                // 2. Think: Ask Gemini
                _uiState.value = CommandUiState.Executing(stepCount + 1, maxSteps, "Thinking...")
                
                // Allow Gemini to see history of recent actions
                val decision = planBuilder.getGeminiClient().decideNextStep(
                     goal = command,
                     history = history.toString(),
                     screenImage = bitmap,
                     uiTree = uiTree
                )

                if (decision == null) {
                     _uiState.value = CommandUiState.Error("Gemini failed to make a decision.")
                     return@launch
                }
                
                // 3. Act: Parse and Execute
                // Format: ACTION|TARGET|VALUE|REASONING
                // Use regex to find the action line in case Gemini adds conversational text
                val actionRegex = Regex("""(OPEN_APP|CLICK|TYPE_TEXT|SCROLL_DOWN|SCROLL_UP|PRESS_ENTER|WAIT|TASK_COMPLETE)\|""")
                val match = actionRegex.find(decision)
                
                if (match != null) {
                    val startIndex = match.range.first
                    val actionLine = decision.substring(startIndex).lines().first()
                    val parts = actionLine.split("|")
                    
                    val actionStr = parts[0].trim()
                    
                    if (actionStr == "TASK_COMPLETE") {
                        _uiState.value = CommandUiState.Success
                        return@launch
                    }
                    
                    val target = if (parts.size > 1) parts[1].trim() else ""
                    val value = if (parts.size > 2) parts[2].trim() else null
                    val reasoning = if (parts.size > 3) parts[3].trim() else "Executing action"
                    
                    try {
                        val actionType = com.crazy.agent.agent.ActionType.valueOf(actionStr)
                        val step = AgentStep(actionType, target, value, reasoning)
                        
                        _uiState.value = CommandUiState.Executing(stepCount + 1, maxSteps, reasoning)
                        
                        val result = actionExecutor.executeStep(step)
                        
                        // Log execution
                        val execution = StepExecution(step, result, stepCount)
                        _executedSteps.value = _executedSteps.value + execution
                        
                        // Update History
                        history.append("Step ${stepCount + 1}: $actionLine -> Result: ${if(result is ActionResult.Success) "Success" else "Fail: ${(result as ActionResult.Failure).error}"}\n")
                        
                    } catch (e: Exception) {
                        Timber.e("Invalid action type (enum parse): $actionStr")
                        // history.append("Step ${stepCount + 1}: Failed to parse action $actionStr\n")
                    }
                } else {
                     Timber.e("No valid action found in decision: $decision")
                     // history.append("Step ${stepCount + 1}: Gemini output invalid format\n")
                }
                
                stepCount++
                // Quick delay to allow UI to settle before next capture?
                // ActionExecutor already waits, but a small buffer helps.
                kotlinx.coroutines.delay(1000)
            }
            
            _uiState.value = CommandUiState.Error("Max steps reached without completion.")
        }
    }
    
    // Helper to access client via PlanBuilder if not directly injected (PlanBuilder holds it)
    // Or we can modify constructor. For minimal change, adding getter to PlanBuilder is easier.


    /** Reset to idle state */
    fun reset() {
        _uiState.value = CommandUiState.Idle
        _executedSteps.value = emptyList()
    }
}

/** UI state for command execution */
sealed class CommandUiState {
    object Idle : CommandUiState()
    object Parsing : CommandUiState()
    data class Executing(
            val currentStep: Int,
            val totalSteps: Int,
            val currentDescription: String
    ) : CommandUiState()
    object Success : CommandUiState()
    data class Error(val message: String) : CommandUiState()
}

/** Represents a step execution with its result */
data class StepExecution(val step: AgentStep, val result: ActionResult, val index: Int)
