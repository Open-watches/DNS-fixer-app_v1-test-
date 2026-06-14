package com.community.dnsfix

import android.graphics.Bitmap
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
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

        Scaffold(
            topBar = { TopAppBar(title = { Text("Handwriting Simulator") }) }
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
                            val generator = HandwritingGenerator(1000, 1200)
                            resultBitmap = generator.generateBitmap(textInput)
                        } else {
                            Toast.makeText(context, "Please enter text", Toast.LENGTH_SHORT).show()
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Generate Handwriting")
                }

                Spacer(modifier = Modifier.height(8.dp))

                Button(
                    onClick = {
                        resultBitmap?.let { bitmap ->
                            saveBitmap(bitmap, context)
                        } ?: Toast.makeText(context, "Generate first", Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Export as PNG")
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Preview area with scrolling
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                ) {
                    resultBitmap?.let {
                        androidx.compose.foundation.Image(
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