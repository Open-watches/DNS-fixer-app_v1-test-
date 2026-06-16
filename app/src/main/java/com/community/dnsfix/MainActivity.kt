package com.community.dnsfix

import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Parcelable
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.community.dnsfix.handwriting.HandwritingGenerator
import com.community.dnsfix.handwriting.NumberModifier
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
        val context = LocalContext.current
        val clipboardManager = LocalClipboardManager.current
        val scope = rememberCoroutineScope()
        val snackbarHostState = remember { SnackbarHostState() }

        // Metadata configs
        var pageNumberInput by remember { mutableStateOf("1") }
        var dateInput by remember { mutableStateOf("16/6/2026") }

        // Core dataset for unconstrained multi-placement control
        var chunkList by remember { mutableStateOf(listOf<HandwritingGenerator.AbsoluteTextChunk>()) }
        var resultBitmap by remember { mutableStateOf<Bitmap?>(null) }

        // Number modification preferences
        var mmNumeralsToggle by remember { mutableStateOf(false) }
        var microSpacingToggle by remember { mutableStateOf(true) }
        val numberModifier = remember(mmNumeralsToggle, microSpacingToggle) {
            NumberModifier(
                convertToMyanmarNumerals = mmNumeralsToggle,
                extraDigitSpacing = microSpacingToggle
            )
        }

        // Tap-to-place structural tracking targets
        var showPlacementDialog by remember { mutableStateOf(false) }
        var targetedCanvasX by remember { mutableStateOf(0f) }
        var targetedCanvasY by remember { mutableStateOf(0f) }
        var newChunkText by remember { mutableStateOf("") }

        // Tracking bounds of preview container for coordinate interpolation scaling
        var displayLayoutWidth by remember { mutableStateOf(1f) }
        var displayLayoutHeight by remember { mutableStateOf(1f) }

        val canvasBaseWidth = 1600f
        val canvasBaseHeight = 2200f

        var lastErrorMsg by remember { mutableStateOf<String?>(null) }
        var lastErrorTrace by remember { mutableStateOf<String?>(null) }
        var generationFailed by remember { mutableStateOf(false) }

        // Trigger dynamic canvas updates whenever structural pieces modify
        LaunchedEffect(chunkList, pageNumberInput, dateInput) {
            val generator = HandwritingGenerator(canvasBaseWidth.toInt(), canvasBaseHeight.toInt(), context)
            resultBitmap = generator.generateBitmapAtCoordinates(
                chunks = chunkList,
                pageNumber = pageNumberInput,
                dateText = dateInput
            )
        }

        Scaffold(
            topBar = { TopAppBar(title = { Text("Physical Notebook Workspace") }) },
            snackbarHost = { SnackbarHost(snackbarHostState) }
        ) { paddingValues ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .padding(14.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                // Number modification configuration cards
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text("Number Processing Modifiers", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Convert to Myanmar Digits (၁, ၂, ၃)", fontSize = 13.sp)
                            Switch(checked = mmNumeralsToggle, onCheckedChange = { mmNumeralsToggle = it })
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Anti-Bleed Microspacing (Prevents Overlap)", fontSize = 13.sp)
                            Switch(checked = microSpacingToggle, onCheckedChange = { microSpacingToggle = it })
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Standard Page Info Input Fields
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = dateInput,
                        onValueChange = { dateInput = it },
                        label = { Text("Header Date") },
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = pageNumberInput,
                        onValueChange = { pageNumberInput = it },
                        label = { Text("Page No.") },
                        modifier = Modifier.weight(0.6f),
                        singleLine = true
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = "👇 TAP ANYWHERE ON THE PAPER PREVIEW BELOW TO WRITE TEXT",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                )

                Spacer(modifier = Modifier.height(6.dp))

                // Interactive Auto-Scaling Paper Preview Target Box
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(480.dp)
                        .background(Color.White, shape = RoundedCornerShape(4.dp))
                        .border(1.dp, Color.LightGray, shape = RoundedCornerShape(4.dp))
                        .onGloballyPositioned { layoutCoordinates ->
                            displayLayoutWidth = layoutCoordinates.size.width.toFloat()
                            displayLayoutHeight = layoutCoordinates.size.height.toFloat()
                        }
                        .pointerInput(Unit) {
                            detectTapGestures { rawOffset ->
                                // Compute high-resolution mapping target using interpolation scaling
                                targetedCanvasX = (rawOffset.x / displayLayoutWidth) * canvasBaseWidth
                                targetedCanvasY = (rawOffset.y / displayLayoutHeight) * canvasBaseHeight
                                newChunkText = ""
                                showPlacementDialog = true
                            }
                        }
                ) {
                    resultBitmap?.let {
                        Image(
                            bitmap = it.asImageBitmap(),
                            contentDescription = "Notebook sheet preview engine",
                            modifier = Modifier.fillMaxSize()
                        )
                    } ?: Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Output Management Action Row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = {
                            resultBitmap?.let { bitmap -> saveToGallery(bitmap, context) }
                                ?: Toast.makeText(context, "Render layer empty", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Save Gallery")
                    }

                    Button(
                        onClick = {
                            resultBitmap?.let { bitmap -> shareBitmap(bitmap, context) }
                                ?: Toast.makeText(context, "Render layer empty", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.weight(1f),
                        enabled = resultBitmap != null
                    ) {
                        Icon(Icons.Default.Share, contentDescription = "Share Document")
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Share Document")
                    }
                }

                // Node Structure Trace Log (Allows individual item deletion management)
                if (chunkList.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(14.dp))
                    Text("Placed Text Blocks Fragment Logs:", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(6.dp)) {
                            chunkList.forEachIndexed { idx, item ->
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "[${idx + 1}] \"${item.text}\" at (${item.x.toInt()}, ${item.y.toInt()})",
                                        fontSize = 12.sp,
                                        modifier = Modifier.weight(1f)
                                    )
                                    IconButton(
                                        onClick = { chunkList = chunkList.toMutableList().apply { removeAt(idx) } },
                                        modifier = Modifier.size(32.dp)
                                    ) {
                                        Icon(Icons.Default.Delete, contentDescription = "Drop node", tint = Color.Red)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // Coordinate Selection Input Dialog Box
        if (showPlacementDialog) {
            AlertDialog(
                onDismissRequest = { showPlacementDialog = false },
                title = { Text("Write Element on Canvas") },
                text = {
                    Column {
                        Text(
                            text = "Target Canvas Scale Vector: X: ${targetedCanvasX.toInt()}px, Y: ${targetedCanvasY.toInt()}px",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.Gray
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(
                            value = newChunkText,
                            onValueChange = { newChunkText = it },
                            label = { Text("Enter text / numerals") },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            if (newChunkText.isNotBlank()) {
                                // Apply customized NumberModifier formatting adjustments automatically
                                val formattedInput = numberModifier.modifyString(newChunkText)
                                
                                val placementChunk = HandwritingGenerator.AbsoluteTextChunk(
                                    text = formattedInput,
                                    x = targetedCanvasX,
                                    y = targetedCanvasY
                                )
                                chunkList = chunkList + placementChunk
                            }
                            showPlacementDialog = false
                        }
                    ) {
                        Text("Inject to Spot")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showPlacementDialog = false }) {
                        Text("Cancel")
                    }
                }
            )
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
                putExtra(Intent.EXTRA_STREAM, uri as Parcelable)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(shareIntent, "Share Handwriting"))
        } catch (e: Exception) {
            Toast.makeText(context, "Share failed: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
}
