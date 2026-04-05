package com.vaultmind.app.rag

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    viewModel: ChatViewModel,
    vaultId: String,
    vaultName: String,
    onBack: () -> Unit,
    onImport: () -> Unit
) {
    LaunchedEffect(vaultId) {
        viewModel.setVault(vaultId)
    }

    val messages by viewModel.messages.collectAsStateWithLifecycle()
    val modelState by viewModel.modelState.collectAsStateWithLifecycle()
    val isGenerating by viewModel.isGenerating.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()

    // Auto-scroll to bottom on new messages
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(vaultName, fontWeight = FontWeight.Bold)
                        if (modelState != ModelLoadState.Ready) {
                            Text(
                                text = when (modelState) {
                                    ModelLoadState.Loading -> "Loading model…"
                                    is ModelLoadState.Error -> "Model error"
                                    ModelLoadState.NotLoaded -> "Model not loaded"
                                    else -> ""
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                actions = {
                    IconButton(onClick = onImport) {
                        Icon(Icons.Filled.Add, contentDescription = "Import data")
                    }
                    IconButton(onClick = viewModel::clearChat) {
                        Icon(Icons.Filled.DeleteSweep, contentDescription = "Clear chat")
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            if (modelState == ModelLoadState.Loading) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            // Message list
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (messages.isEmpty()) {
                    item {
                        EmptyStateHint(vaultName = vaultName)
                    }
                }
                items(messages, key = { it.id }) { message ->
                    MessageBubble(message = message)
                }
            }

            // Input bar
            ChatInputBar(
                enabled = modelState == ModelLoadState.Ready && !isGenerating,
                isGenerating = isGenerating,
                onSend = viewModel::sendMessage,
                onStop = viewModel::stopGeneration,
                modifier = Modifier
                    .fillMaxWidth()
                    .imePadding()
                    .navigationBarsPadding()
            )
        }
    }
}

@Composable
private fun EmptyStateHint(vaultName: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 48.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "Ask anything about your \"$vaultName\" notes.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun MessageBubble(message: ChatMessage) {
    val isUser = message.isUser
    val clipboardManager = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    var showCopied by remember { mutableStateOf(false) }
    val bubbleColor = if (isUser) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    val textColor = if (isUser) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        Column(
            modifier = Modifier.widthIn(max = 320.dp),
            horizontalAlignment = if (isUser) Alignment.End else Alignment.Start
        ) {
            Surface(
                shape = RoundedCornerShape(
                    topStart = if (isUser) 16.dp else 4.dp,
                    topEnd = if (isUser) 4.dp else 16.dp,
                    bottomStart = 16.dp,
                    bottomEnd = 16.dp
                ),
                color = bubbleColor
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    if (message.isStreaming && message.text.isEmpty()) {
                        // Phase indicator
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(14.dp),
                                strokeWidth = 2.dp
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = message.statusHint ?: "Generating…",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    } else {
                        Text(
                            text = message.text,
                            color = textColor,
                            style = MaterialTheme.typography.bodyMedium
                        )
                        if (message.isStreaming) {
                            Spacer(Modifier.height(4.dp))
                            // Blinking cursor indicator
                            Box(
                                modifier = Modifier
                                    .size(8.dp, 14.dp)
                                    .background(textColor, RoundedCornerShape(1.dp))
                            )
                        }
                    }
                }
            }

            // Copy button (shown after streaming is done)
            if (!message.isStreaming && message.text.isNotBlank()) {
                Row(
                    modifier = Modifier.padding(start = 4.dp, top = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = {
                            clipboardManager.setText(AnnotatedString(message.text))
                            showCopied = true
                            scope.launch {
                                delay(1500)
                                showCopied = false
                            }
                        },
                        modifier = Modifier.size(24.dp)
                    ) {
                        Icon(
                            Icons.Filled.ContentCopy,
                            contentDescription = "Copy response",
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (showCopied) {
                        Spacer(Modifier.width(4.dp))
                        Text(
                            "Copied",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }

            // Source attribution (collapsible)
            if (!isUser && message.sources.isNotEmpty() && !message.isStreaming) {
                SourcesSection(sources = message.sources)
            }
        }
    }
}

@Composable
private fun SourcesSection(sources: List<RAGSource>) {
    var expanded by remember { mutableStateOf(false) }

    // Compute average similarity across all sources
    val avgSimilarity = if (sources.isNotEmpty()) sources.map { it.similarity }.average().toFloat() else 0f

    Spacer(modifier = Modifier.height(4.dp))
    Row(
        modifier = Modifier
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Average similarity indicator
        SimilarityDot(similarity = avgSimilarity)
        Spacer(Modifier.width(4.dp))
        Text(
            text = "${sources.size} source${if (sources.size > 1) "s" else ""} · ${"%d".format((avgSimilarity * 100).toInt())}% avg match",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary
        )
        IconButton(
            onClick = { expanded = !expanded },
            modifier = Modifier.size(20.dp)
        ) {
            Icon(
                imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                contentDescription = if (expanded) "Hide sources" else "Show sources",
                modifier = Modifier.size(14.dp)
            )
        }
    }

    AnimatedVisibility(
        visible = expanded,
        enter = expandVertically(),
        exit = shrinkVertically()
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            sources.forEachIndexed { i, source ->
                SourceCard(index = i, source = source)
            }
        }
    }
}

@Composable
private fun SourceCard(index: Int, source: RAGSource) {
    var showFull by remember { mutableStateOf(false) }

    Card(
        onClick = { showFull = !showFull },
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Row(
            modifier = Modifier.padding(8.dp),
            verticalAlignment = Alignment.Top
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "[${index + 1}] ${if (showFull) source.fullText else source.text}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (source.fullText.length > 120) {
                    Text(
                        text = if (showFull) "Show less" else "Show full chunk",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                SimilarityDot(similarity = source.similarity)
                Text(
                    text = "${"%d".format((source.similarity * 100).toInt())}%",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/** Small colored dot: green for high similarity, yellow for medium, red for low. */
@Composable
private fun SimilarityDot(similarity: Float) {
    val color = when {
        similarity >= 0.7f -> Color(0xFF4CAF50) // green
        similarity >= 0.4f -> Color(0xFFFFC107) // amber
        else -> Color(0xFFEF5350)                // red
    }
    Box(
        modifier = Modifier
            .size(8.dp)
            .background(color, CircleShape)
    )
}

@Composable
private fun ChatInputBar(
    enabled: Boolean,
    isGenerating: Boolean,
    onSend: (String) -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier
) {
    var text by remember { mutableStateOf("") }

    Surface(
        modifier = modifier,
        shadowElevation = 8.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                placeholder = { Text("Ask about your notes…") },
                modifier = Modifier.weight(1f),
                enabled = enabled,
                maxLines = 4,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = {
                    if (text.isNotBlank()) { onSend(text); text = "" }
                }),
                shape = RoundedCornerShape(24.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            if (isGenerating) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(48.dp)
                ) {
                    IconButton(onClick = onStop) {
                        Icon(
                            Icons.Filled.Stop,
                            contentDescription = "Stop generation",
                            tint = MaterialTheme.colorScheme.onError
                        )
                    }
                }
            } else {
                Surface(
                    shape = CircleShape,
                    color = if (enabled && text.isNotBlank())
                        MaterialTheme.colorScheme.primary
                    else
                        MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.size(48.dp)
                ) {
                    IconButton(
                        onClick = {
                            if (text.isNotBlank()) { onSend(text); text = "" }
                        },
                        enabled = enabled && text.isNotBlank()
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.Send,
                            contentDescription = "Send",
                            tint = if (enabled && text.isNotBlank())
                                MaterialTheme.colorScheme.onPrimary
                            else
                                MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}
