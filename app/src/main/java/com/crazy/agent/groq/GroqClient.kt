package com.crazy.agent.groq

import android.graphics.Bitmap
import com.crazy.agent.BuildConfig
import com.google.gson.Gson
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import timber.log.Timber

@Singleton
class GroqClient @Inject constructor() {

    private val client = OkHttpClient()
    private val gson = Gson()
    private val apiKey = BuildConfig.GROQ_API_KEY
    private val modelName = "openai/gpt-oss-120b" // Best free model currently available

    suspend fun decideNextStep(
        goal: String,
        history: String,
        screenImage: Bitmap, // Unused for text-only model
        uiTree: String
    ): AgentAction? = withContext(Dispatchers.IO) {
        var attempts = 0
        val maxRetries = 10 // Increased from 3 to allow more retries
        var currentBackoff = 2000L

        while (attempts < maxRetries) {
            try {
                // Truncate UI tree if it's too large to avoid token limits
                // Llama 3.3 70b has large context, but let's keep it reasonable. 50k chars is plenty.
                /*val processedUiTree = if (uiTree.length > 50000) {
                     uiTree.substring(0, 50000) + "... [TRUNCATED]"
                } else {
                     uiTree
                }*/

                val processedUiTree = uiTree

                val prompt = """
You are a **deterministic Android UI Agent** that controls apps step by step.

Your task is to decide the **SINGLE best next action** to achieve the USER GOAL.
You must avoid loops, repeated actions, and invalid UI assumptions.

══════════════════════════════════════
USER GOAL:
"$goal"

ACTION HISTORY (most recent last):
$history

CURRENT SCREEN (OPTIMIZED UI TREE):
The UI tree is now optimized with:
- "index": Position in lists (0-based, e.g., 0=first, 1=second, 2=third)
- "type": Element type (video, button, search_field, input, list)
- "pos": Vertical position (top, mid, bot)
- "id": Shortened resource ID
- "click", "edit", "scroll": Boolean flags

$processedUiTree
══════════════════════════════════════

ABSOLUTE RULES (DO NOT VIOLATE):

──────────────────────────────────────
PHASE 0 — APP OPENING (MANDATORY)
──────────────────────────────────────
1. If the required app is NOT open:
   → You MUST return OPEN_APP first.
2. If the current package is "com.crazy.agent":
   → ALWAYS return OPEN_APP for the TARGET app (e.g. YouTube).
   ❌ NEVER return "OPEN_APP|com.crazy.agent" (That is YOU).
3. NO other actions are allowed before OPEN_APP succeeds.

──────────────────────────────────────
PHASE 1 — POPUP/MODAL DETECTION
──────────────────────────────────────
4. ALWAYS check for popups/modals FIRST before proceeding:
   - Look for: "Allow", "Skip", "Not now", "No thanks", "Cancel", "X", "Close", "Dismiss", "OK", "Got it"
   - If ANY popup/modal is detected → CLICK to dismiss it IMMEDIATELY
   - Only proceed with main task after all popups are cleared

──────────────────────────────────────
PHASE 2 — TODO LIST MANAGEMENT
──────────────────────────────────────
5. Maintain a mental TODO list for the goal:
   - Break down the goal into sub-tasks
   - Track what's completed and what's remaining
   - Include this in your reasoning field
   Example for "Open YouTube and play despacito":
   TODO: [✓] Open YouTube, [✓] Dismiss popups, [ ] Click search, [ ] Type song, [ ] Press enter, [ ] Select video

──────────────────────────────────────
PHASE 3 — UI INTERACTION
──────────────────────────────────────
6. ONE ACTION ONLY
   - Output exactly ONE action as JSON.
   - No explanations outside the JSON.

7. NO REPEATED ACTIONS
   - If the same action (same type + same target) exists in HISTORY,
     assume it FAILED → do NOT repeat it.
   - Try alternative approaches (different text, scroll, wait)

8. STATE AWARENESS
   - CLICK only visible elements in the UI tree.
   - TYPE_TEXT only if an editable field exists.
   - If loading or uncertain → WAIT.

9. EFFICIENCY RULES (CRITICAL)
   - Use CLICK_INDEX whenever elements have "index" field
   - If user says "3rd video", use CLICK_INDEX with value="2" (0-based)
   - Prefer resource IDs ("id" field) over text matching
   - Check if target is visible before scrolling

──────────────────────────────────────
SEARCH FLOW (STRICT ORDER)
──────────────────────────────────────
10. Search MUST follow this order:
    a) CLICK search icon / field
    b) TYPE_TEXT search query
    c) PRESS_ENTER
    d) Wait for results
    e) Use CLICK_INDEX to select by position OR CLICK by text
❌ Never skip steps or click results early.

──────────────────────────────────────
FAILURE & RECOVERY LOGIC
──────────────────────────────────────
11. CLICK FAILURE RULE
    If a CLICK did not change the screen:
    → Try different TEXT or CONTENT_DESCRIPTION
    → OR SCROLL_DOWN to find element
    → OR WAIT for UI to settle
    ❌ Never repeat the same CLICK

12. LOOP PREVENTION
    - If no progress after 2 actions:
      → SCROLL or WAIT
    - If stuck in a loop:
      → Try completely different approach
    - If nothing can advance the goal:
      → TASK_COMPLETE

──────────────────────────────────────
SCROLL INTELLIGENCE
──────────────────────────────────────
13. If target likely exists but not visible in UI tree:
    → SCROLL_DOWN (once, then reassess)
    → Check UI tree again after scroll

──────────────────────────────────────
FINISH CONDITION
──────────────────────────────────────
14. When the goal is clearly achieved:
    → TASK_COMPLETE
    ❌ Do NOT over-act or continue unnecessarily

══════════════════════════════════════
OUTPUT FORMAT (STRICT JSON):

You MUST respond with a valid JSON object in this exact format:

{
  "action": "ACTION_TYPE",
  "target": "target_value_or_null",
  "value": "value_or_null",
  "reasoning": "Brief explanation with TODO progress",
  "todo_status": "[✓] Done items, [ ] Pending items"
}

AVAILABLE ACTION TYPES:
- OPEN_APP (target: package.name, value: null)
- CLICK (target: Exact Text OR Resource ID, value: null)
- CLICK_INDEX (target: element type like "video", value: index like "2" for 3rd item)
- TYPE_TEXT (target: Field Hint or ID, value: Text to type)
- PRESS_ENTER (target: null, value: null)
- SCROLL_DOWN (target: null, value: null)
- SCROLL_UP (target: null, value: null)
- WAIT (target: milliseconds, value: null)
- TASK_COMPLETE (target: null, value: null)

EXAMPLES:

{
  "action": "OPEN_APP",
  "target": "com.google.android.youtube",
  "value": null,
  "reasoning": "Opening YouTube app to start the task",
  "todo_status": "[ ] Open YouTube, [ ] Search for song, [ ] Play video"
}

{
  "action": "CLICK",
  "target": "Skip",
  "value": null,
  "reasoning": "Dismissing popup to proceed with main task",
  "todo_status": "[✓] Open YouTube, [ ] Dismiss popups, [ ] Search for song"
}

{
  "action": "TYPE_TEXT",
  "target": "Search",
  "value": "despacito",
  "reasoning": "Typing song name in search field",
  "todo_status": "[✓] Open YouTube, [✓] Click search, [ ] Type song, [ ] Press enter"
}

{
  "action": "CLICK_INDEX",
  "target": "video",
  "value": "0",
  "reasoning": "Selecting first video from search results",
  "todo_status": "[✓] Open YouTube, [✓] Search complete, [ ] Play video"
}

{
  "action": "CLICK_INDEX",
  "target": "video",
  "value": "2",
  "reasoning": "User requested 3rd video (index 2, 0-based)",
  "todo_status": "[✓] Open YouTube, [✓] Search complete, [ ] Play 3rd video"
}

══════════════════════════════════════
OUTPUT RULES:
- ONLY output valid JSON
- NO markdown code blocks
- NO extra text before or after JSON
- Ensure all strings are properly escaped

══════════════════════════════════════
DECIDE THE NEXT ACTION (JSON ONLY):
""".trimIndent()

                val messages = listOf(
                    GroqMessage("system", "You are a helpful Android Agent. Output ONLY valid JSON with no markdown formatting."),
                    GroqMessage("user", prompt)
                )

                val requestBody = GroqRequest(
                    model = modelName,
                    messages = messages
//                    temperature = 0.1f,
//                    max_tokens = 1000,
//                    top_p = 0.9f
                )

                val jsonBody = gson.toJson(requestBody)
                
                val request = Request.Builder()
                    .url("https://api.groq.com/openai/v1/chat/completions")
                    .addHeader("Authorization", "Bearer $apiKey")
                    .addHeader("Content-Type", "application/json")
//                    .addHeader("HTTP-Referer", "https://github.com/Start-Of-The-Week/App-Agent") // Required by OpenRouter
//                    .addHeader("X-Title", "Android App Agent")
                    .post(jsonBody.toRequestBody("application/json; charset=utf-8".toMediaType()))
                    .build()

                val response = client.newCall(request).execute()
                val responseBody = response.body?.string()

                if (response.code == 429) {
                     // Rate limit hit - wait 25 seconds as requested by user
                     val retryAfter = response.header("Retry-After")?.toLongOrNull()?.times(1000) ?: 25000L
                     Timber.w("Rate limit hit. Waiting ${retryAfter}ms before retry ${attempts + 1}/$maxRetries")
                     response.close()
                     kotlinx.coroutines.delay(retryAfter)
                     attempts++
                     continue
                }

                if (!response.isSuccessful) {
                    Timber.e("Groq API error: ${response.code} - $responseBody")
                    return@withContext null
                }

                if (responseBody != null) {
                    val groqResponse = gson.fromJson(responseBody, GroqResponse::class.java)
                    val content = groqResponse.choices.firstOrNull()?.message?.content?.trim()
                    Timber.i("Groq raw response: $content")
                    
                    if (content != null) {
                        try {
                            // Parse JSON response
                            val cleanContent = content
                                .removePrefix("```json")
                                .removePrefix("```")
                                .removeSuffix("```")
                                .trim()
                            
                            val action = gson.fromJson(cleanContent, AgentAction::class.java)
                            Timber.i("Parsed action: ${action.action} | ${action.target} | ${action.value}")
                            Timber.i("Reasoning: ${action.reasoning}")
                            Timber.i("TODO Status: ${action.todo_status}")
                            return@withContext action
                        } catch (e: Exception) {
                            Timber.e(e, "Failed to parse JSON response: $content")
                            return@withContext null
                        }
                    }
                }
                
                return@withContext null
            } catch (e: Exception) {
                Timber.e(e, "Error deciding next step with Groq")
                return@withContext null
            }
        }
        return@withContext null
    }
    
    // Data classes for JSON parsing
    private data class GroqRequest(
        val model: String,
        val messages: List<GroqMessage>,
//        val temperature: Float,
//        val max_tokens: Int,
//        val top_p: Float
    )

    private data class GroqMessage(
        val role: String,
        val content: String
    )

    private data class GroqResponse(
        val choices: List<GroqChoice>
    )

    private data class GroqChoice(
        val message: GroqMessage
    )
    
    // Data class for structured agent action response
    data class AgentAction(
        val action: String,
        val target: String?,
        val value: String?,
        val reasoning: String,
        val todo_status: String
    )
}
