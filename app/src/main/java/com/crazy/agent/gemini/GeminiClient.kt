package com.crazy.agent.gemini

import com.crazy.agent.BuildConfig
import com.google.ai.client.generativeai.GenerativeModel
import com.google.ai.client.generativeai.type.generationConfig
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber

@Singleton
class GeminiClient @Inject constructor() {

    private val model =
            GenerativeModel(
                    // Use specific version to ensure availability
                    modelName = "gemini-2.5-flash",
                    apiKey = BuildConfig.GEMINI_API_KEY,
                    generationConfig =
                            generationConfig {
                                temperature = 0.2f // Low temperature for deterministic output
                                topK = 40
                                topP = 0.95f
                                maxOutputTokens = 8192
                            }
            )

    suspend fun decideNextStep(
            goal: String,
            history: String,
            screenImage: android.graphics.Bitmap,
            uiTree: String
    ): String? =
            withContext(Dispatchers.IO) {
                try {
                    val prompt =
                            """
                You are an intelligent Android Agent. Your goal is to accomplish the user's request on the device.
                
                USER GOAL: "$goal"
                
                HISTORY:
                $history
                
                CURRENT STATE:
                - See the attached screenshot.
                - The UI Tree is provided below (simplified JSON):
                $uiTree
                
                INSTRUCTIONS:
                1. Analyze the Screenshot and UI Tree to understand the current screen.
                2. Determine the SINGLE, NEXT action to move closer to the goal.
                3. If the goal is complete, return "TASK_COMPLETE".
                4. If you need to scroll to find something, use "SCROLL_DOWN" or "SCROLL_UP".
                5. If you need to click, prefer using the "id" or "text" from the UI Tree.
                6. CRITICAL: If you are seeing the "Agent App" screen (command input), do NOT click "Execute" again. Proceed directly to OPEN_APP or the first logical step of the user's goal.
                
                AVAILABLE ACTIONS:
                - OPEN_APP|PACKAGE_NAME|null : Opens an app (Guess package name e.g., com.whatsapp, com.google.android.youtube).
                - CLICK|TARGET_TEXT_OR_ID|null : Clicks a UI element.
                - TYPE_TEXT|TARGET_HINT_OR_ID|TEXT : Types text into a field.
                - SCROLL_DOWN|null|null : Scrolls down.
                - SCROLL_UP|null|null : Scrolls up.
                - PRESS_ENTER|null|null : Presses enter/submit.
                - WAIT|DURATION_MS|null : Explicit wait.
                - TASK_COMPLETE|null|null : Finished.
                
                OUTPUT FORMAT:
                ACTION|TARGET|VALUE|REASONING
                (Do not add any conversational text before or after. Just the action line.)
            """.trimIndent()

                    val inputContent = com.google.ai.client.generativeai.type.content {
                        image(screenImage)
                        text(prompt)
                    }

                    val response = model.generateContent(inputContent)
                    Timber.i("Gemini decision: ${response.text}")
                    return@withContext response.text
                } catch (e: Exception) {
                    Timber.e(e, "Error deciding next step with Gemini")
                    return@withContext null
                }
            }

    // Legacy method for backward compatibility (or we can remove/deprecate it)
    suspend fun generatePlan(userCommand: String, history: String? = null): String? {
         // ... (Keep existing implementation or redirect to decideNextStep if appropriate, 
         // but for now keeping it as separate strategy)
         return null 
    }
}
