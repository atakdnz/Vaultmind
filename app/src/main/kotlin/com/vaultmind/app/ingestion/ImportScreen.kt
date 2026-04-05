package com.vaultmind.app.ingestion

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportScreen(
    viewModel: ImportViewModel,
    vaultId: String,
    vaultName: String,
    onBack: () -> Unit
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    var pendingTxtUri by remember { mutableStateOf<Uri?>(null) }
    var pendingRvaultUri by remember { mutableStateOf<Uri?>(null) }
    var rvaultPassword by remember { mutableStateOf("") }

    val txtPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            pendingTxtUri = uri
            viewModel.importTxt(uri, vaultId)
        }
    }

    // Request write permission alongside read so DocumentsContract.deleteDocument()
    // can remove the .rvault file after a successful import.
    val rvaultPicker = rememberLauncherForActivityResult(
        contract = object : ActivityResultContracts.OpenDocument() {
            override fun createIntent(context: android.content.Context, input: Array<String>) =
                super.createIntent(context, input).apply {
                    addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                }
        }
    ) { uri ->
        pendingRvaultUri = uri
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Add to \"$vaultName\"") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            when (val s = state) {
                ImportUiState.Idle -> {
                    ImportIdleContent(
                        onPickTxt = { txtPicker.launch(arrayOf("text/plain")) },
                        onPickRvault = { rvaultPicker.launch(arrayOf("*/*")) },
                        pendingRvaultUri = pendingRvaultUri,
                        rvaultPassword = rvaultPassword,
                        onPasswordChange = { rvaultPassword = it },
                        onImportRvault = {
                            pendingRvaultUri?.let { uri ->
                                viewModel.importRvault(uri, rvaultPassword.toCharArray(), vaultId)
                                rvaultPassword = ""
                            }
                        }
                    )
                }

                is ImportUiState.InProgress -> {
                    ImportProgressContent(s.progress)
                }

                is ImportUiState.Done -> {
                    ImportDoneContent(
                        chunksAdded = s.chunksAdded,
                        onAddMore = { viewModel.reset(); pendingRvaultUri = null },
                        onBack = onBack
                    )
                }

                is ImportUiState.Error -> {
                    Text(
                        text = "Error: ${s.message}",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Button(onClick = { viewModel.reset(); pendingRvaultUri = null }) {
                        Text("Try Again")
                    }
                }
            }
        }
    }
}

@Composable
private fun ImportIdleContent(
    onPickTxt: () -> Unit,
    onPickRvault: () -> Unit,
    pendingRvaultUri: Uri?,
    rvaultPassword: String,
    onPasswordChange: (String) -> Unit,
    onImportRvault: () -> Unit
) {
    Text(
        text = "On-Device Import",
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold
    )
    Text(
        text = "Pick a .txt file — it will be chunked and embedded entirely on this device. The original file is never copied into app storage.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Button(
        onClick = onPickTxt,
        modifier = Modifier.fillMaxWidth()
    ) {
        Icon(Icons.Filled.FileUpload, contentDescription = null)
        Spacer(Modifier.size(8.dp))
        Text("Pick .txt File")
    }

    Spacer(modifier = Modifier.height(8.dp))

    Text(
        text = "PC Import (.rvault)",
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold
    )
    Text(
        text = "Import a pre-built vault package from your computer using vault_builder.py.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )

    OutlinedButton(
        onClick = onPickRvault,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(if (pendingRvaultUri != null) "File selected — enter password" else "Pick .rvault File")
    }

    if (pendingRvaultUri != null) {
        OutlinedTextField(
            value = rvaultPassword,
            onValueChange = onPasswordChange,
            label = { Text("Package password") },
            visualTransformation = PasswordVisualTransformation(),
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Button(
            onClick = onImportRvault,
            enabled = rvaultPassword.isNotBlank(),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Decrypt & Import")
        }
    }
}

@Composable
private fun ImportProgressContent(progress: IngestionProgress) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        CircularProgressIndicator(modifier = Modifier.size(48.dp))
        Spacer(modifier = Modifier.height(24.dp))
        Text(
            text = progress.phase,
            style = MaterialTheme.typography.bodyLarge
        )
        if (progress.total > 0) {
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "${progress.current} / ${progress.total}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = { progress.current.toFloat() / progress.total },
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun ImportDoneContent(
    chunksAdded: Int,
    onAddMore: () -> Unit,
    onBack: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Filled.CheckCircle,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(64.dp)
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "Import complete",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "$chunksAdded chunks added to vault",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(32.dp))
        Button(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
            Text("Go to Vault")
        }
        Spacer(modifier = Modifier.height(12.dp))
        OutlinedButton(onClick = onAddMore, modifier = Modifier.fillMaxWidth()) {
            Text("Add More")
        }
    }
}
