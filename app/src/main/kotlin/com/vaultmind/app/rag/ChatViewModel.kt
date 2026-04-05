package com.vaultmind.app.rag

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vaultmind.app.ingestion.EmbeddingEngine
import com.vaultmind.app.settings.AppPreferences
import com.vaultmind.app.vault.VaultRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.drop
import javax.inject.Inject

/** A single message in the chat history. */
data class ChatMessage(
    val id: Long = System.nanoTime(),
    val isUser: Boolean,
    val text: String,
    val sources: List<String> = emptyList(),   // chunk excerpts for attribution
    val isStreaming: Boolean = false
)

sealed class ModelLoadState {
    data object NotLoaded : ModelLoadState()
    data object Loading : ModelLoadState()
    data object Ready : ModelLoadState()
    data class Error(val message: String) : ModelLoadState()
}

@HiltViewModel
class ChatViewModel @Inject constructor(
    private val llmEngine: LlmEngine,
    private val embeddingEngine: EmbeddingEngine,
    private val vectorSearch: VectorSearch,
    private val promptBuilder: PromptBuilder,
    private val vaultRepository: VaultRepository,
    private val appPreferences: AppPreferences
) : ViewModel() {

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _modelState = MutableStateFlow<ModelLoadState>(ModelLoadState.NotLoaded)
    val modelState: StateFlow<ModelLoadState> = _modelState.asStateFlow()

    private val _isGenerating = MutableStateFlow(false)
    val isGenerating: StateFlow<Boolean> = _isGenerating.asStateFlow()

    // Settings (adjustable per session)
    private val _topK = MutableStateFlow(5)
    val topK: StateFlow<Int> = _topK.asStateFlow()

    private val _minSimilarity = MutableStateFlow(0.3f)

    private val _thinkingMode = MutableStateFlow(true)
    val thinkingMode: StateFlow<Boolean> = _thinkingMode.asStateFlow()

    // Keep last N turns for prompt context (conversation memory)
    private val historyTurns = mutableListOf<PromptBuilder.Turn>()
    private val maxHistoryTurns = 4

    private var activeVaultId: String? = null

    init {
        // When the vault is locked (vaults list becomes empty after being non-empty),
        // clear all in-memory chat state immediately — don't wait for onCleared().
        viewModelScope.launch {
            vaultRepository.vaults
                .drop(1) // skip initial emission
                .collect { vaults ->
                    if (vaults.isEmpty() && activeVaultId != null) {
                        _messages.value = emptyList()
                        historyTurns.clear()
                        activeVaultId = null
                        _modelState.value = ModelLoadState.NotLoaded
                        _isGenerating.value = false
                    }
                }
        }
    }

    fun setVault(vaultId: String) {
        activeVaultId = vaultId
        if (!llmEngine.isLoaded() && _modelState.value == ModelLoadState.NotLoaded) {
            viewModelScope.launch {
                val settings = appPreferences.settings.first()
                _topK.value = settings.topK
                _thinkingMode.value = settings.thinkingMode
                if (settings.llmModelPath.isNotBlank() && settings.embeddingModelPath.isNotBlank()) {
                    loadModels(settings.llmModelPath, settings.embeddingModelPath, settings.temperature)
                }
            }
        }
    }

    /** Load both models (LLM + embedding). Show loading state. */
    fun loadModels(llmModelPath: String, embeddingModelPath: String, temperature: Float = 0.3f) {
        if (_modelState.value == ModelLoadState.Loading) return
        _modelState.value = ModelLoadState.Loading

        viewModelScope.launch {
            try {
                embeddingEngine.load(embeddingModelPath)
                llmEngine.load(llmModelPath, temperature)
                _modelState.value = ModelLoadState.Ready
            } catch (e: Exception) {
                _modelState.value = ModelLoadState.Error("Failed to load model: ${e.message}")
            }
        }
    }

    /** Send a user message, run RAG, stream the response. */
    fun sendMessage(userText: String) {
        val vaultId = activeVaultId ?: return
        if (userText.isBlank() || _isGenerating.value) return

        val userMsg = ChatMessage(isUser = true, text = userText.trim())
        val streamingMsg = ChatMessage(isUser = false, text = "", isStreaming = true)

        _messages.value = _messages.value + userMsg + streamingMsg
        _isGenerating.value = true

        viewModelScope.launch {
            try {
                // Step 1: Embed the query
                val queryVector = embeddingEngine.embedQuery(userText)
                    ?: run {
                        appendError("Embedding model not ready. Please load models first.")
                        return@launch
                    }

                // Step 2: Vector search
                val vaultDb = vaultRepository.openVaultDb(vaultId)
                val results = vectorSearch.search(
                    vaultDb = vaultDb,
                    queryVector = queryVector,
                    topK = _topK.value,
                    minSimilarity = _minSimilarity.value
                )
                queryVector.fill(0f)

                // Step 3: Build RAG prompt
                val prompt = promptBuilder.buildRagPrompt(
                    question = userText,
                    retrievedChunks = results,
                    history = historyTurns.takeLast(maxHistoryTurns),
                    thinkingMode = _thinkingMode.value
                )

                // Step 4: Stream response
                val responseBuilder = StringBuilder()
                llmEngine.generateStream(prompt).collect { token ->
                    responseBuilder.append(token)
                    // Update the streaming message in-place
                    val current = _messages.value.toMutableList()
                    val streamIdx = current.indexOfLast { !it.isUser && it.isStreaming }
                    if (streamIdx >= 0) {
                        current[streamIdx] = current[streamIdx].copy(
                            text = responseBuilder.toString()
                        )
                        _messages.value = current
                    }
                }

                // Step 5: Finalise the message (stop streaming indicator)
                val finalResponse = cleanResponse(responseBuilder.toString())
                val sourceExcerpts = results.map { it.content.take(100) + "…" }

                val current = _messages.value.toMutableList()
                val streamIdx = current.indexOfLast { !it.isUser && it.isStreaming }
                if (streamIdx >= 0) {
                    current[streamIdx] = current[streamIdx].copy(
                        text = finalResponse,
                        sources = sourceExcerpts,
                        isStreaming = false
                    )
                    _messages.value = current
                }

                // Step 6: Update history (ephemeral — not persisted to disk)
                historyTurns.add(PromptBuilder.Turn(userText, finalResponse))
                if (historyTurns.size > maxHistoryTurns) {
                    historyTurns.removeAt(0)
                }
            } catch (e: Exception) {
                appendError("Error: ${e.message}")
            } finally {
                _isGenerating.value = false
            }
        }
    }

    /**
     * Remove thinking/reasoning tokens from the response before showing to user.
     * Gemma 4's <|think|> block ends before the actual answer.
     */
    private fun cleanResponse(raw: String): String {
        // If thinking mode was on, the model starts with <|think|>...<|/think|> then the answer
        val thinkEndIdx = raw.indexOf("<|/think|>")
        return if (thinkEndIdx >= 0) {
            raw.substring(thinkEndIdx + "<|/think|>".length).trim()
        } else {
            raw.trim()
        }
    }

    private fun appendError(message: String) {
        val current = _messages.value.toMutableList()
        // Replace streaming placeholder if it exists
        val streamIdx = current.indexOfLast { !it.isUser && it.isStreaming }
        if (streamIdx >= 0) {
            current[streamIdx] = current[streamIdx].copy(text = message, isStreaming = false)
        } else {
            current.add(ChatMessage(isUser = false, text = message))
        }
        _messages.value = current
        _isGenerating.value = false
    }

    /** Clear conversation history. Vault data is NOT affected. */
    fun clearChat() {
        _messages.value = emptyList()
        historyTurns.clear()
    }

    fun setTopK(k: Int) { _topK.value = k.coerceIn(1, 15) }
    fun setThinkingMode(enabled: Boolean) { _thinkingMode.value = enabled }

    override fun onCleared() {
        super.onCleared()
        llmEngine.unload()
    }
}
