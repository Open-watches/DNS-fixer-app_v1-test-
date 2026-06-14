package com.community.dnsfix

import android.graphics.Bitmap
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import java.io.FileOutputStream

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                HandwritingApp()
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun HandwritingApp() {
        var textInput by remember { mutableStateOf("") }
        var resultBitmap by remember { mutableStateOf<Bitmap?>(null) }
        val context = LocalContext.current
        val clipboardManager = LocalClipboardManager.current
        val scope = rememberCoroutineScope()
        val snackbarHostState = remember { SnackbarHostState() }

        // Error state
        var lastErrorMsg by remember { mutableStateOf<String?>(null) }
        var lastErrorTrace by remember { mutableStateOf<String?>(null) }
        var generationFailed by remember { mutableStateOf(false) }

        Scaffold(
            topBar = { TopAppBar(title = { Text("Handwriting Simulator") }) },
            snackbarHost = { SnackbarHost(snackbarHostState) }
        ) { paddingValues ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .padding(16.dp)
            ) {
                OutlinedTextField(
                    value = textInput,
                    onValueChange = { textInput = it },
                    label = { Text("Type your note (Myanmar supported)") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3
                )

                Spacer(modifier = Modifier.height(8.dp))

                Button(
                    onClick = {
                        if (textInput.isNotBlank()) {
                            val generator = HandwritingGenerator(1000, 1200, context)
                            resultBitmap = generator.generateBitmap(textInput)
                            val (msg, trace) = generator.getLastError()
                            if (msg != null) {
                                lastErrorMsg = msg
                                lastErrorTrace = trace
                                generationFailed = true
                                scope.launch {
                                    snackbarHostState.showSnackbar(
                                        message = "Error: $msg",
                                        actionLabel = "Copy Log",
                                        withDismiss = true
                                    )
                                }
                            } else {
                                lastErrorMsg = null
                                lastErrorTrace = null
                                generationFailed = false
                            }
                        } else {
                            Toast.makeText(context, "Please enter text", Toast.LENGTH_SHORT).show()
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Generate Handwriting")
                }

                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = {
                            resultBitmap?.let { bitmap ->
                                saveBitmap(bitmap, context)
                            } ?: Toast.makeText(context, "Generate first", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Export as PNG")
                    }

                    // Retry button (arrow) – only shown if generation failed
                    if (generationFailed) {
                        Button(
                            onClick = {
                                // Re-run generation with same text
                                val generator = HandwritingGenerator(1000, 1200, context)
                                resultBitmap = generator.generateBitmap(textInput)
                                val (msg, trace) = generator.getLastError()
                                if (msg != null) {
                                    lastErrorMsg = msg
                                    lastErrorTrace = trace
                                    scope.launch {
                                        snackbarHostState.showSnackbar(
                                            message = "Error: $msg",
                                            actionLabel = "Copy Log",
                                            withDismiss = true
                                        )
                                    }
                                } else {
                                    lastErrorMsg = null
                                    lastErrorTrace = null
                                    generationFailed = false
                                }
                            },
                            modifier = Modifier.weight(0.3f)
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = "Retry")
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Retry")
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                ) {
                    resultBitmap?.let {
                        Image(
                            bitmap = it.asImageBitmap(),
                            contentDescription = "Handwriting preview",
                            modifier = Modifier.fillMaxWidth()
                        )
                    } ?: Box(modifier = Modifier.fillMaxSize()) {
                        Text(
                            "Your handwritten note will appear here",
                            modifier = Modifier.align(Alignment.Center)
                        )
                    }
                }
            }
        }

        // Handle snackbar action (copy log)
        LaunchedEffect(snackbarHostState) {
            snackbarHostState.currentSnackbarData?.let { data ->
                if (data.actionLabel == "Copy Log") {
                    lastErrorTrace?.let { trace ->
                        clipboardManager.setText(buildAnnotatedString { append(trace) })
                        Toast.makeText(context, "Error log copied to clipboard", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    private fun saveBitmap(bitmap: Bitmap, context: android.content.Context) {
        try {
            val file = context.getExternalFilesDir(null)?.absolutePath + "/note_${System.currentTimeMillis()}.png"
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            Toast.makeText(context, "Saved to $file", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
}