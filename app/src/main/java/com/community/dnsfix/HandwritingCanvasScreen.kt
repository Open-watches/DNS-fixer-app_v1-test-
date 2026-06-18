package com.community.dnsfix.ui

import android.graphics.Bitmap
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.community.dnsfix.handwriting.HandwritingGenerator
import com.community.dnsfix.handwriting.NumberModifier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

enum class EditorMode { Write, Move }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HandwritingCanvasScreen(
    generator: HandwritingGenerator,
    pageNumber: String = "1",
    dateText: String = "16/06/2026",
    numberModifier: NumberModifier = NumberModifier(false, true),
    onBitmapReady: (Bitmap) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusRequester = remember { FocusRequester() }

    val paperWidthPx = generator.paperWidthPx
    val paperHeightPx = generator.paperHeightPx
    val paperWidthDp = with(density) { paperWidthPx.toDp() }
    val paperHeightDp = with(density) { paperHeightPx.toDp() }

    var chunkList by remember { mutableStateOf(listOf<HandwritingGenerator.AbsoluteTextChunk>()) }
    var currentBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var isGenerating by remember { mutableStateOf(false) }

    var editorMode by remember { mutableStateOf(EditorMode.Write) }
    var cursorPos by remember { mutableStateOf<Offset?>(null) }
    var liveText by remember { mutableStateOf("") }
    var selectedChunkIndex by remember { mutableStateOf<Int?>(null) }

    val undoStack = remember { mutableStateListOf<List<HandwritingGenerator.AbsoluteTextChunk>>() }
    val redoStack = remember { mutableStateListOf<List<HandwritingGenerator.AbsoluteTextChunk>>() }

    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }

    val infiniteTransition = rememberInfiniteTransition()
    val blinkAlpha by infiniteTransition.animateFloat(
        initialValue = 0.2f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(500, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        )
    )

    val lineSpacing = 72f
    val marginTop = 180f

    fun snapY(y: Float): Float {
        val lineIdx = ((y - marginTop) / lineSpacing).roundToInt().coerceAtLeast(0)
        return marginTop + lineIdx * lineSpacing
    }

    fun regenerateBitmap() {
        if (isGenerating) return
        isGenerating = true
        scope.launch {
            val allChunks = if (cursorPos != null && liveText.isNotEmpty()) {
                chunkList + HandwritingGenerator.AbsoluteTextChunk(liveText.trim(), cursorPos!!.x, cursorPos!!.y)
            } else chunkList

            val bmp = withContext(Dispatchers.Default) {
                generator.generateBitmapAtCoordinates(allChunks, pageNumber, dateText)
            }
            currentBitmap = bmp
            onBitmapReady(bmp)
            isGenerating = false
        }
    }

    LaunchedEffect(Unit) { regenerateBitmap() }
    LaunchedEffect(chunkList, liveText, cursorPos, pageNumber, dateText) {
        regenerateBitmap()
    }

    fun pushUndo() {
        undoStack.add(chunkList)
        redoStack.clear()
    }

    fun addChunk(chunk: HandwritingGenerator.AbsoluteTextChunk) {
        pushUndo()
        chunkList = chunkList + chunk
    }

    fun moveChunk(index: Int, dx: Float, dy: Float) {
        if (dx == 0f && dy == 0f) return
        pushUndo()
        val old = chunkList[index]
        chunkList = chunkList.toMutableList().apply {
            this[index] = old.copy(x = old.x + dx, y = old.y + dy)
        }
    }

    fun deleteChunk(index: Int) {
        pushUndo()
        chunkList = chunkList.toMutableList().apply { removeAt(index) }
        selectedChunkIndex = null
    }

    fun undo() {
        if (undoStack.isNotEmpty()) {
            redoStack.add(chunkList)
            chunkList = undoStack.removeLast()
            selectedChunkIndex = null
        }
    }

    fun redo() {
        if (redoStack.isNotEmpty()) {
            undoStack.add(chunkList)
            chunkList = redoStack.removeLast()
            selectedChunkIndex = null
        }
    }

    fun commitLiveText() {
        val text = liveText.trim()
        if (cursorPos != null && text.isNotEmpty()) {
            addChunk(HandwritingGenerator.AbsoluteTextChunk(text, cursorPos!!.x, cursorPos!!.y))
        }
        cursorPos = null
        liveText = ""
        focusRequester.freeFocus()
        keyboardController?.hide()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Handwriting Editor") },
                actions = {
                    Button(
                        onClick = {
                            if (chunkList.isNotEmpty()) {
                                pushUndo()
                                chunkList = emptyList()
                                selectedChunkIndex = null
                            }
                        },
                        enabled = !isGenerating,
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                    ) { Text("Clear") }
                }
            )
        },
        bottomBar = {
            BottomAppBar {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = {
                        editorMode = if (editorMode == EditorMode.Write) EditorMode.Move else EditorMode.Write
                        selectedChunkIndex = null
                        if (cursorPos != null && liveText.isNotEmpty()) commitLiveText()
                    }) {
                        Icon(
                            imageVector = if (editorMode == EditorMode.Write) Icons.Default.Edit else Icons.Default.PanTool,
                            contentDescription = "Mode",
                            tint = if (editorMode == EditorMode.Write) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary
                        )
                    }
                    IconButton(onClick = { undo() }, enabled = undoStack.isNotEmpty()) {
                        Icon(Icons.Default.Undo, contentDescription = "Undo")
                    }
                    IconButton(onClick = { redo() }, enabled = redoStack.isNotEmpty()) {
                        Icon(Icons.Default.Redo, contentDescription = "Redo")
                    }
                    IconButton(
                        onClick = { selectedChunkIndex?.let { deleteChunk(it) } },
                        enabled = selectedChunkIndex != null && editorMode == EditorMode.Move
                    ) {
                        Icon(Icons.Default.Delete, contentDescription = "Delete")
                    }
                    TextButton(
                        onClick = { commitLiveText() },
                        enabled = cursorPos != null && liveText.isNotBlank()
                    ) { Text("Done") }
                }
            }
        }
    ) { innerPadding ->
        Box(
            modifier = modifier
                .padding(innerPadding)
                .fillMaxSize()
                .background(Color(0xFFF9F6F0))
        ) {
            BasicTextField(
                value = liveText,
                onValueChange = { newText ->
                    liveText = numberModifier.modifyString(newText)
                },
                modifier = Modifier
                    .alpha(0f)
                    .focusRequester(focusRequester)
                    .onFocusChanged { focusState ->
                        if (!focusState.isFocused && cursorPos != null && liveText.isNotBlank()) {
                            commitLiveText()
                        }
                    },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { commitLiveText() })
            )

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            scale *= zoom
                            offset = offset + pan
                        }
                    }
                    .pointerInput(editorMode, chunkList, scale, offset) {
                        awaitPointerEventScope {
                            while (true) {
                                val event = awaitPointerEvent()
                                val firstPointer = event.changes.firstOrNull() ?: continue

                                when {
                                    !firstPointer.pressed && firstPointer.previousPressed -> {
                                        val rawPos = firstPointer.position
                                        val paperPos = (rawPos - offset) / scale

                                        if (editorMode == EditorMode.Write) {
                                            val snappedY = snapY(paperPos.y)
                                            cursorPos = Offset(paperPos.x, snappedY)
                                            liveText = ""
                                            focusRequester.requestFocus()
                                            keyboardController?.show()
                                        } else {
                                            selectedChunkIndex = chunkList.indexOfLast { chunk ->
                                                val w = generator.estimateTextWidth(chunk.text)
                                                val rect = Rect(
                                                    chunk.x - 5f, chunk.y - 30f,
                                                    chunk.x + w + 5f, chunk.y + 20f
                                                )
                                                rect.contains(paperPos)
                                            }.takeIf { it >= 0 }
                                        }
                                    }

                                    editorMode == EditorMode.Move && selectedChunkIndex != null &&
                                    firstPointer.pressed && firstPointer.positionChanged() -> {
                                        val delta = firstPointer.position - firstPointer.previousPosition
                                        val paperDelta = Offset(delta.x / scale, delta.y / scale)
                                        moveChunk(selectedChunkIndex!!, paperDelta.x, paperDelta.y)
                                    }
                                }
                            }
                        }
                    }
            ) {
                Box(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                            translationX = offset.x
                            translationY = offset.y
                        }
                        .size(paperWidthDp, paperHeightDp)
                ) {
                    if (currentBitmap != null) {
                        Canvas(modifier = Modifier.fillMaxSize()) {
                            drawImage(currentBitmap!!.asImageBitmap())

                            if (cursorPos != null && editorMode == EditorMode.Write) {
                                val alpha = blinkAlpha
                                val cx = cursorPos!!.x
                                val cy = cursorPos!!.y
                                drawLine(Color.Black.copy(alpha), Offset(cx - 12f, cy), Offset(cx + 12f, cy), 1.5f)
                                drawLine(Color.Black.copy(alpha), Offset(cx, cy - 12f), Offset(cx, cy + 12f), 1.5f)
                            }

                            if (selectedChunkIndex != null && editorMode == EditorMode.Move) {
                                val chunk = chunkList[selectedChunkIndex!!]
                                val w = generator.estimateTextWidth(chunk.text)
                                val rectTopLeft = Offset(chunk.x - 4f, chunk.y - 28f)
                                val rectSize = Size(w + 8f, 46f)

                                drawRect(Color.Blue.copy(alpha = 0.2f), rectTopLeft, rectSize)
                                drawRect(Color.Blue.copy(alpha = 0.8f), rectTopLeft, rectSize, style = Stroke(2f))
                                drawRect(Color.Blue, rectTopLeft, Size(8f, 8f))
                                drawRect(Color.Blue, Offset(chunk.x + w - 4f, chunk.y + 18f), Size(8f, 8f))
                            }
                        }
                    } else {
                        CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                    }
                }
            }
        }
    }
}