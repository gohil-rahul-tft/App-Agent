package com.crazy.agent.agent

/** Represents a single step in an agent's execution plan */
data class AgentStep(
        val action: ActionType,
        val target: String,
        val value: String? = null,
        val description: String
)

enum class ActionType {
    OPEN_APP,
    FIND_ELEMENT,
    CLICK,
    TYPE_TEXT,
    PRESS_ENTER,
    SEARCH,
    WAIT,
    NAVIGATE_BACK
}
