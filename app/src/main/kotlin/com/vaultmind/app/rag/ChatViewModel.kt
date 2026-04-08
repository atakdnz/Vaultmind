package com.vaultmind.app.rag

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vaultmind.app.ingestion.EmbeddingEngine
import com.vaultmind.app.settings.AppPreferences
import com.vaultmind.app.vault.VaultRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import android.util.Log
import javax.inject.Inject

/** A single source retrieved from the local vault. */
data class RAGSource(val text: String, val fullText: String, val similarity: Float)

/** A single message in the chat history. */
data class ChatMessage(
    val id: Long = System.nanoTime(),
    val isUser: Boolean,
    val text: String,
    val sources: List<RAGSource> = emptyList(),   // chunk excerpts for attribution
    val isStreaming: Boolean = false,
    val statusHint: String? = null  // "Processing…", "Thinking…", or null
)

@HiltViewModel
class ChatViewModel @Inject constructor(
    private val llmEngine: LlmEngine,
    private val embeddingEngine: EmbeddingEngine,
    private val vectorSearch: VectorSearch,
    private val promptBuilder: PromptBuilder,
    private val vaultRepository: VaultRepository,
    private val appPreferences: AppPreferences,
    private val modelSessionManager: ModelSessionManager
) : ViewModel() {

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    val modelState: StateFlow<ModelLoadState> = modelSessionManager.state

    private val _isGenerating = MutableStateFlow(false)
    val isGenerating: StateFlow<Boolean> = _isGenerating.asStateFlow()

    private val _vaultChunkCount = MutableStateFlow<Int?>(null)
    val vaultChunkCount: StateFlow<Int?> = _vaultChunkCount.asStateFlow()

    // Settings (adjustable per session)
    private val _topK = MutableStateFlow(5)
    val topK: StateFlow<Int> = _topK.asStateFlow()

    private val _minSimilarity = MutableStateFlow(0.3f)

    private val _thinkingMode = MutableStateFlow(true)
    val thinkingMode: StateFlow<Boolean> = _thinkingMode.asStateFlow()

    private val _userInstructions = MutableStateFlow<String>("")
    val userInstructions: StateFlow<String> = _userInstructions.asStateFlow()

    // Keep last N turns for prompt context (conversation memory)
    private val historyTurns = mutableListOf<PromptBuilder.Turn>()
    private val maxHistoryTurns = 4

    private var activeVaultId: String? = null
    private var generationJob: Job? = null

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
                        _vaultChunkCount.value = null
                        _userInstructions.value = ""
                        _isGenerating.value = false
                    }
                }
        }
    }

    fun setVault(vaultId: String) {
        if (activeVaultId != null && activeVaultId != vaultId) {
            generationJob?.cancel()
            generationJob = null
            clearChat()
            _vaultChunkCount.value = null
            _userInstructions.value = ""
        }
        activeVaultId = vaultId
        refreshVaultState()
    }

    fun refreshVaultState() {
        val vaultId = activeVaultId ?: return
        viewModelScope.launch {
            try {
                val settings = appPreferences.settings.first()
                _topK.value = settings.topK
                _thinkingMode.value = settings.thinkingMode

                val vaultDb = vaultRepository.openVaultDb(vaultId)
                _userInstructions.value = vaultDb.getVaultInfo("user_instructions") ?: ""
                val chunkCount = vaultDb.getChunkCount()
                _vaultChunkCount.value = chunkCount

                if (chunkCount <= 0) {
                    return@launch
                }

                modelSessionManager.ensureLoaded()
            } catch (_: Exception) {
                _vaultChunkCount.value = null
            }
        }
    }

    /** Send a user message, run RAG, stream the response. */
    fun sendMessage(userText: String) {
        val vaultId = activeVaultId ?: return
        if ((_vaultChunkCount.value ?: 0) <= 0) return
        if (userText.isBlank() || _isGenerating.value) return

        val userMsg = ChatMessage(isUser = true, text = userText.trim())
        val streamingMsg = ChatMessage(isUser = false, text = "", isStreaming = true, statusHint = "Processing…")

        _messages.value = _messages.value + userMsg + streamingMsg
        _isGenerating.value = true

        generationJob = viewModelScope.launch {
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
                    thinkingMode = _thinkingMode.value,
                    userInstructions = _userInstructions.value.ifBlank { null }
                )

                // Update status — model is now reading the prompt (prefill)
                updateStreamingHint("Preparing response…")

                // Step 4: Stream response
                val responseBuilder = StringBuilder()
                llmEngine.generateStream(prompt).collect { token ->
                    responseBuilder.append(token)
                    // Update the streaming message in-place
                    val current = _messages.value.toMutableList()
                    val streamIdx = current.indexOfLast { !it.isUser && it.isStreaming }
                    if (streamIdx >= 0) {
                        current[streamIdx] = current[streamIdx].copy(
                            text = responseBuilder.toString(),
                            statusHint = null
                        )
                        _messages.value = current
                    }
                }

                // Step 5: Finalise the message (stop streaming indicator)
                val finalResponse = cleanResponse(responseBuilder.toString())
                val sourceExcerpts = results.map {
                    RAGSource(
                        text = it.content.take(120) + if (it.content.length > 120) "…" else "",
                        fullText = it.content,
                        similarity = it.score
                    )
                }

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

    /** Save user instructions for this vault. */
    fun saveUserInstructions(instructions: String) {
        val vaultId = activeVaultId ?: return
        _userInstructions.value = instructions
        viewModelScope.launch {
            try {
                val vaultDb = vaultRepository.openVaultDb(vaultId)
                vaultDb.setVaultInfo("user_instructions", instructions)
            } catch (e: Exception) {
                Log.e("ChatViewModel", "Failed to save user instructions", e)
            }
        }
    }

    /** Clear conversation history. Vault data is NOT affected. */
    fun clearChat() {
        _messages.value = emptyList()
        historyTurns.clear()
    }

    fun setTopK(k: Int) { _topK.value = k.coerceIn(1, 15) }
    fun setThinkingMode(enabled: Boolean) { _thinkingMode.value = enabled }

    /** Stop the current generation. Cancels the coroutine which triggers conversation.cancelProcess(). */
    fun stopGeneration() {
        generationJob?.cancel()
        generationJob = null
        // Finalize the streaming message with whatever text we have so far
        val current = _messages.value.toMutableList()
        val streamIdx = current.indexOfLast { !it.isUser && it.isStreaming }
        if (streamIdx >= 0) {
            val partial = current[streamIdx].text.let { cleanResponse(it) }
            if (partial.isBlank()) {
                current.removeAt(streamIdx)
            } else {
                current[streamIdx] = current[streamIdx].copy(
                    text = partial + "\n\n[Stopped]",
                    isStreaming = false,
                    statusHint = null
                )
            }
            _messages.value = current
        }
        _isGenerating.value = false
    }

    private fun updateStreamingHint(hint: String) {
        val current = _messages.value.toMutableList()
        val streamIdx = current.indexOfLast { !it.isUser && it.isStreaming }
        if (streamIdx >= 0) {
            current[streamIdx] = current[streamIdx].copy(statusHint = hint)
            _messages.value = current
        }
    }

    override fun onCleared() {
        super.onCleared()
        generationJob?.cancel()
    }
}
