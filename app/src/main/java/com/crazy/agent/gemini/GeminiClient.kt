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

    suspend fun generatePlan(userCommand: String): String? =
            withContext(Dispatchers.IO) {
                try {
                    val prompt =
                            """
                You are an intelligent Android Agent planning assistant. Your goal is to convert a user's natural language command into a list of steps to executed on an Android device.
                
                The available actions are:
                - OPEN_APP: Opens an app. Target is the package name (e.g., com.whatsapp).
                - FIND_ELEMENT: Finds a UI element on the screen. Target is the text or content description.
                - CLICK: Clicks on a UI element. Target is the text or content description of the element to click.
                - TYPE_TEXT: Types text into a field. Target is the field hint/text, Value is the text to type.
                - PRESS_ENTER: Presses the Enter/Submit key on the keyboard. Target is usually the input field name (just for context).
                - WAIT: Waits for a duration in milliseconds. Target is the duration (e.g., "1000").
                - SEARCH: Performs a search. Target is usually unused, Value is the query. (Prefer FIND_ELEMENT + TYPE_TEXT + PRESS_ENTER over this if possible for more control).
                
                For the user command: "$userCommand"
                
                Generate a JSON-like execution plan. NOT valid JSON, just a simple format I can parse easily.
                Format for each step:
                ACTION|TARGET|VALUE|DESCRIPTION
                
                Rules:
                1. Always start by opening the correct app. Guess the package name if possible (e.g. youtube -> com.google.android.youtube, whatsapp -> com.whatsapp, spotify -> com.spotify.music).
                2. If the user wants to play something, typically: Open App -> Wait -> Find "Search" button -> Click "Search" -> Find Search Bar -> Type Query -> Press Enter -> Wait -> Find Query/Video -> Click.
                3. If the user wants to message: Open App -> Wait -> Find New Chat/Search -> Click -> Type Name -> Wait -> Click Name -> Wait -> Type Message -> Press Enter/Click Send.
                
                Response should ONLY contain the lines with the steps. No markdown, no explanations.
                
                Example Output:
                OPEN_APP|com.google.android.youtube|null|Opening YouTube
                WAIT|2000|null|Waiting for app to load
                FIND_ELEMENT|Search|null|Finding search button
                CLICK|Search|null|Clicking search
                FIND_ELEMENT|Search YouTube|null|Finding search bar
                TYPE_TEXT|Search YouTube|Despacito|Typing query
                PRESS_ENTER|Search YouTube|null|Submitting search
                WAIT|2000|null|Waiting for results
                FIND_ELEMENT|Despacito|null|Finding video
                CLICK|Despacito|null|Playing video
            """.trimIndent()

                    val response = model.generateContent(prompt)
                    Timber.i("Generated plan: ${response.text}")
                    return@withContext response.text
                } catch (e: Exception) {
                    Timber.e(e, "Error generating plan with Gemini")
                    return@withContext null
                }
            }
}
