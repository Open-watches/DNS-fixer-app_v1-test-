package com.community.dnsfix.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.community.dnsfix.handwriting.HandwritingGenerator
import com.community.dnsfix.handwriting.NumberModifier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HandwritingCanvasScreen(
    generator: HandwritingGenerator,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()

    // Use the generator’s known paper size to lock the drawing area exactly.
    val paperWidthPx = generator.paperWidthPx
    val paperHeightPx = generator.paperHeightPx
    val paperWidthDp = with(density) { paperWidthPx.toDp() }
    val paperHeightDp = with(density) { paperHeightPx.toDp() }

    // Core state
    var chunkList by remember { mutableStateOf(listOf<HandwritingGenerator.AbsoluteTextChunk>()) }
    var currentBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var isGenerating by remember { mutableStateOf(false) }

    // Dialog state
    var showInputDialog by remember { mutableStateOf(false) }
    var tapOffset by remember { mutableStateOf(Offset.Zero) }
    var enteredText by remember { mutableStateOf("") }

    // Number processing options
    val numberModifier = remember {
        NumberModifier(convertToMyanmarNumerals = false, extraDigitSpacing = true)
    }

    // Bitmap regeneration – always off the main thread
    fun regenerateBitmap() {
        if (isGenerating) return
        isGenerating = true
        scope.launch {
            val bmp = withContext(Dispatchers.Default) {
                generator.generateBitmapAtCoordinates(
                    chunks = chunkList,
                    pageNumber = "1",
                    dateText = "16/06/2026"
                )
            }
            currentBitmap = bmp
            isGenerating = false
        }
    }

    // Initial blank paper
    LaunchedEffect(Unit) {
        regenerateBitmap()
    }

    // Re‑render whenever the chunk list changes
    LaunchedEffect(chunkList) {
        regenerateBitmap()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Absolute Paper Workspace") },
                actions = {
                    Button(
                        onClick = {
                            chunkList = emptyList()
                        },
                        enabled = !isGenerating,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Text("Clear Sheet")
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = modifier
                .padding(innerPadding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "Tap anywhere on the paper to place text or numbers",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(12.dp)
            )

            // Fixed‑size paper surface – coordinates now match 1:1 with bitmap pixels
            Box(
                modifier = Modifier
                    .padding(16.dp)
                    .size(width = paperWidthDp, height = paperHeightDp)   // exactly matches the generated bitmap
                    .background(Color.White)
                    .pointerInput(Unit) {
                        detectTapGestures { offset ->
                            if (!isGenerating) {
                                tapOffset = offset
                                enteredText = ""
                                showInputDialog = true
                            }
                        }
                    }
            ) {
                if (currentBitmap != null) {
                    Image(
                        bitmap = currentBitmap!!.asImageBitmap(),
                        contentDescription = "Handwritten paper surface",
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    // Initial placeholder while first bitmap loads
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.LightGray),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator()
                    }
                }

                // Overlay a subtle progress indicator while regenerating
                if (isGenerating) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.05f)),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator()
                    }
                }
            }
        }
    }

    // Text input dialog
    if (showInputDialog) {
        AlertDialog(
            onDismissRequest = { showInputDialog = false },
            title = {
                Text("Write at (${tapOffset.x.toInt()}, ${tapOffset.y.toInt()})")
            },
            text = {
                Column {
                    OutlinedTextField(
                        value = enteredText,
                        onValueChange = { enteredText = it },
                        label = { Text("Text or numerals") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val trimmed = enteredText.trim()
                        if (trimmed.isNotBlank()) {
                            val processed = numberModifier.modifyString(trimmed)
                            chunkList = chunkList + HandwritingGenerator.AbsoluteTextChunk(
                                text = processed,
                                x = tapOffset.x,
                                y = tapOffset.y
                            )
                        }
                        showInputDialog = false
                    }
                ) {
                    Text("Place")
                }
            },
            dismissButton = {
                TextButton(onClick = { showInputDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}