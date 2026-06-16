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
import androidx.compose.ui.unit.dp
import com.community.dnsfix.handwriting.HandwritingGenerator
import com.community.dnsfix.handwriting.NumberModifier

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HandwritingCanvasScreen(
    generator: HandwritingGenerator,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    
    // State management for explicit canvas elements
    var chunkList by remember { mutableStateOf(listOf<HandwritingGenerator.AbsoluteTextChunk>()) }
    var currentBitmap by remember { mutableStateOf<Bitmap?>(null) }
    
    // Dialog state controllers
    var showInputDialog by remember { mutableStateOf(false) }
    var tapOffset by remember { mutableStateOf(Offset.Zero) }
    var enteredText by remember { mutableStateOf("") }
    
    // Number configuration options
    val numberModifier = remember { 
        NumberModifier(convertToMyanmarNumerals = false, extraDigitSpacing = true) 
    }

    // Automatically trigger an initial blank paper render cycle
    LaunchedEffect(chunkList) {
        currentBitmap = generator.generateBitmapAtCoordinates(
            chunks = chunkList,
            pageNumber = "1",
            dateText = "16/06/2026"
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Absolute Paper Workspace") },
                actions = {
                    Button(
                        onClick = { chunkList = emptyList() },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
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
                text = "Tap anywhere on the paper below to place text or numbers",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(12.dp)
            )

            // Interactive Paper Surface container box
            Box(
                modifier = Modifier
                    .padding(16.dp)
                    .wrapContentSize()
                    .background(Color.White)
                    .pointerInput(Unit) {
                        detectTapGestures { offset ->
                            tapOffset = offset
                            enteredText = ""
                            showInputDialog = true
                        }
                    }
            ) {
                currentBitmap?.let { bmp ->
                    Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = "Handwritten Paper Surface Layout",
                        modifier = Modifier.fillMaxSize()
                    )
                } ?: Box(
                    modifier = Modifier
                        .size(400.dp, 500.dp)
                        .background(Color.LightGray),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            }
        }
    }

    // Coordinate input placement pop-up handler
    if (showInputDialog) {
        AlertDialog(
            onDismissRequest = { showInputDialog = false },
            title = { Text("Write at: X: ${tapOffset.x.toInt()}, Y: ${tapOffset.y.toInt()}") },
            text = {
                Column {
                    OutlinedTextField(
                        value = enteredText,
                        onValueChange = { enteredText = it },
                        label = { Text("Enter text or numerals") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (enteredText.isNotBlank()) {
                            // Run text adjustments through the modification system
                            val sanitizedText = numberModifier.modifyString(enteredText)
                            
                            // Map the output directly into the coordinate slots
                            val newChunk = HandwritingGenerator.AbsoluteTextChunk(
                                text = sanitizedText,
                                x = tapOffset.x,
                                y = tapOffset.y
                            )
                            chunkList = chunkList + newChunk
                        }
                        showInputDialog = false
                    }
                ) {
                    Text("Place Text")
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
