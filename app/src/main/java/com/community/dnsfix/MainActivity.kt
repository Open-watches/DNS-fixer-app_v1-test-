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
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.community.dnsfix.handwriting.HandwritingGenerator
import com.community.dnsfix.handwriting.NumberModifier
import com.community.dnsfix.ui.HandwritingCanvasScreen
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                MainScreen()
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun MainScreen() {
        val context = LocalContext.current
        val scope = rememberCoroutineScope()

        // ---- Configurable parameters ----
        var pageNumber by remember { mutableStateOf("1") }
        var dateText by remember { mutableStateOf("16/6/2026") }
        var mmNumerals by remember { mutableStateOf(false) }
        var microSpacing by remember { mutableStateOf(true) }

        // The handwriting generator – created once per canvas size (here fixed)
        val generator = remember {
            HandwritingGenerator(
                paperWidthPx = 1600,
                paperHeightPx = 2200,
                context = context
            )
        }

        // A shared bitmap that can be retrieved for saving / sharing
        var lastBitmap by remember { mutableStateOf<Bitmap?>(null) }

        // ---- Build the screen ----
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Handwriting Notebook") },
                    actions = {
                        // Save button
                        IconButton(onClick = {
                            lastBitmap?.let {
                                saveToGallery(it, context)
                            } ?: Toast.makeText(context, "No content", Toast.LENGTH_SHORT).show()
                        }) {
                            Icon(Icons.Default.Save, contentDescription = "Save")
                        }
                        // Share button
                        IconButton(onClick = {
                            lastBitmap?.let {
                                shareBitmap(it, context)
                            } ?: Toast.makeText(context, "No content", Toast.LENGTH_SHORT).show()
                        }) {
                            Icon(Icons.Default.Share, contentDescription = "Share")
                        }
                    }
                )
            }
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
            ) {
                // --- Settings panel ---
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(8.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text("Page Settings", fontWeight = FontWeight.Bold, fontSize = 14.sp)

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedTextField(
                                value = dateText,
                                onValueChange = { dateText = it },
                                label = { Text("Date") },
                                modifier = Modifier.weight(1f),
                                singleLine = true
                            )
                            OutlinedTextField(
                                value = pageNumber,
                                onValueChange = { pageNumber = it },
                                label = { Text("Page No.") },
                                modifier = Modifier.weight(0.6f),
                                singleLine = true
                            )
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Myanmar digits", fontSize = 13.sp)
                            Switch(checked = mmNumerals, onCheckedChange = { mmNumerals = it })
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Extra spacing for numbers", fontSize = 13.sp)
                            Switch(checked = microSpacing, onCheckedChange = { microSpacing = it })
                        }
                    }
                }

                // --- The interactive editor ---
                HandwritingCanvasScreen(
                    generator = generator,
                    pageNumber = pageNumber,
                    dateText = dateText,
                    numberModifier = NumberModifier(mmNumerals, microSpacing),
                    onBitmapReady = { bitmap -> lastBitmap = bitmap }
                )
            }
        }
    }

    // --- Save / Share helpers (unchanged from original) ---
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
                Toast.makeText(context, "Saved to Pictures", Toast.LENGTH_LONG).show()
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
            val uri = FileProvider.getUriForFile(
                context, "${context.packageName}.fileprovider", file
            )
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "image/png"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(shareIntent, "Share Handwriting"))
        } catch (e: Exception) {
            Toast.makeText(context, "Share failed: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
}