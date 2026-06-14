package com.community.dnsfix

import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import kotlinx.coroutines.launch
import java.io.File
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
                                    val result = snackbarHostState.showSnackbar(
                                        message = "Error: $msg",
                                        actionLabel = "Copy Log"
                                    )
                                    if (result == SnackbarResult.ActionPerformed) {
                                        trace?.let {
                                            clipboardManager.setText(buildAnnotatedString { append(it) })
                                            Toast.makeText(context, "Error log copied", Toast.LENGTH_SHORT).show()
                                        }
                                    }
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
                                saveToGallery(bitmap, context)
                            } ?: Toast.makeText(context, "Generate first", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Save to Gallery")
                    }

                    Button(
                        onClick = {
                            resultBitmap?.let { bitmap ->
                                shareBitmap(bitmap, context)
                            } ?: Toast.makeText(context, "Generate first", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.weight(1f),
                        enabled = resultBitmap != null
                    ) {
                        Icon(Icons.Default.Share, contentDescription = "Share")
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Share")
                    }

                    if (generationFailed) {
                        Button(
                            onClick = {
                                val generator = HandwritingGenerator(1000, 1200, context)
                                resultBitmap = generator.generateBitmap(textInput)
                                val (msg, trace) = generator.getLastError()
                                if (msg != null) {
                                    lastErrorMsg = msg
                                    lastErrorTrace = trace
                                    scope.launch {
                                        val result = snackbarHostState.showSnackbar(
                                            message = "Error: $msg",
                                            actionLabel = "Copy Log"
                                        )
                                        if (result == SnackbarResult.ActionPerformed) {
                                            trace?.let {
                                                clipboardManager.setText(buildAnnotatedString { append(it) })
                                                Toast.makeText(context, "Error log copied", Toast.LENGTH_SHORT).show()
                                            }
                                        }
                                    }
                                } else {
                                    lastErrorMsg = null
                                    lastErrorTrace = null
                                    generationFailed = false
                                }
                            },
                            modifier = Modifier.weight(0.4f)
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = "Retry")
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
    }

    private fun saveToGallery(bitmap: Bitmap, context: android.content.Context) {
        try {
            val filename = "Handwriting_${System.currentTimeMillis()}.png"
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, filename)
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES)
                }
            }
            val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            uri?.let {
                context.contentResolver.openOutputStream(it)?.use { out ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                }
                Toast.makeText(context, "Saved to Pictures folder", Toast.LENGTH_LONG).show()
            }
        } catch (e: Exception) {
            Toast.makeText(context, "Save failed: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun shareBitmap(bitmap: Bitmap, context: android.content.Context) {
        try {
            val file = File(context.cacheDir, "share_${System.currentTimeMillis()}.png")
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "image/png"
                putExtra(Intent.EXTRA_STREAM, uri as? java.io.Serializable ?: uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(shareIntent, "Share Handwriting"))
        } catch (e: Exception) {
            Toast.makeText(context, "Share failed: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
}