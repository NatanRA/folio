package app.folio.library

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfRenderer
import android.os.Bundle
import android.os.ParcelFileDescriptor
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ArrowForward
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.*
import java.io.Closeable
import java.io.File
import java.util.zip.ZipFile

/** Renders only requested pages. Archive paths are never extracted to the filesystem. */
internal class FixedPages(file: File) : Closeable {
    private val pdf = if (file.extension.equals("pdf", true)) PdfRenderer(ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)) else null
    private val archive = if (pdf == null) ZipFile(file) else null
    private val entries = try { archive?.entries()?.asSequence()?.filter { !it.isDirectory && it.name.substringAfterLast('.').lowercase() in setOf("jpg", "jpeg", "png", "webp") && !it.name.startsWith("__MACOSX/") }?.toList()?.sortedWith(compareBy { naturalKey(it.name) }).orEmpty().also { if (archive != null) require(it.size in 1..5000) { "This CBZ has no supported pages or is too large." } } }
        catch (error: Exception) { archive?.close(); throw error }
    val count get() = pdf?.pageCount ?: entries.size
    init { require(count > 0) { "This document has no pages." } }
    @Synchronized fun render(index: Int, width: Int = 1400): Bitmap {
        require(index in 0 until count)
        pdf?.let { document -> return document.openPage(index).use { page ->
            val w = width.coerceAtMost(2000); val h = (w.toLong() * page.height / page.width).coerceIn(1, 4000).toInt()
            Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.WHITE); page.render(this, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY) }
        } }
        val entry = entries[index]; require(entry.size in 1..50_000_000) { "Comic page is too large." }
        val bytes = archive!!.getInputStream(entry).use { it.readBytesBounded(50_000_000) }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "This comic page cannot be decoded." }
        val options = BitmapFactory.Options().apply { inSampleSize = maxOf(1, maxOf(bounds.outWidth, bounds.outHeight) / 2200) }
        return requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)) { "This comic page cannot be decoded." }
    }
    @Synchronized override fun close() { pdf?.close(); archive?.close() }
    companion object { private fun naturalKey(name: String) = Regex("[0-9]+").replace(name.lowercase()) { it.value.padStart(20, '0') } }
}

@OptIn(ExperimentalMaterial3Api::class)
class FixedReaderActivity : ComponentActivity() {
    private var lightSystemBars = false
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) androidx.core.view.WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = lightSystemBars
            isAppearanceLightNavigationBars = lightSystemBars
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent.getStringExtra("titleId") ?: return finish()
        val title = LibraryStore(this).all().find { it.id == id } ?: return finish()
        val file = title.ebook?.let { LibraryStore(this).file(it) } ?: return finish()
        val prefs = getSharedPreferences("progress", MODE_PRIVATE)
        setContent { PortalTheme {
            val lightBars = MaterialTheme.colorScheme.background.luminance() > .5f
            lightSystemBars = lightBars
            SideEffect { window.decorView.post {
                androidx.core.view.WindowCompat.getInsetsController(window, window.decorView).apply {
                    isAppearanceLightStatusBars = lightBars; isAppearanceLightNavigationBars = lightBars
                }
            } }
            var source by remember { mutableStateOf<FixedPages?>(null) }; var error by remember { mutableStateOf<String?>(null) }
            var picker by remember { mutableStateOf(false) }; var finished by remember { mutableStateOf(ReadingProgress(prefs, id).finished) }
            LaunchedEffect(file) { try { source = withContext(Dispatchers.IO) { FixedPages(file) } } catch (e: Exception) { error = e.message } }
            DisposableEffect(source) { val opened = source; onDispose { opened?.close() } }
            val pages = source
            if (pages == null) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                if (error == null) CircularProgressIndicator() else Column(Modifier.padding(24.dp)) { Text(error!!); TextButton(onClick = { finish() }) { Text("Back to library") } }
            } else {
                val pager = rememberPagerState(initialPage = prefs.getInt("fixed-page:$id", 0).coerceIn(0, pages.count - 1), pageCount = { pages.count })
                val scope = rememberCoroutineScope()
                LaunchedEffect(pager.currentPage) {
                    prefs.edit().putInt("fixed-page:$id", pager.currentPage).apply()
                    val fraction = if (pages.count > 1) pager.currentPage.toDouble() / (pages.count - 1) else 1.0
                    ReadingProgress(prefs, id).record(org.json.JSONObject().put("locations", org.json.JSONObject().put("totalProgression", fraction)).toString(), fraction)
                }
                Scaffold(topBar = { CenterAlignedTopAppBar(title = { Text(title.name, maxLines = 1) }, actions = { TextButton(onClick = { picker = true }) { Text("${pager.currentPage + 1} / ${pages.count}") } }) },
                    bottomBar = { Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(8.dp), horizontalArrangement = Arrangement.Center) {
                        FilledTonalIconButton(onClick = { setResult(Activity.RESULT_OK); finish() }) { Icon(Icons.Outlined.Home, "Home") }
                        FilledTonalIconButton(enabled = pager.currentPage > 0, onClick = { scope.launch { pager.animateScrollToPage(pager.currentPage - 1) } }) { Icon(Icons.Outlined.ArrowBack, "Previous page") }
                        FilledTonalIconButton(enabled = pager.currentPage + 1 < pages.count, onClick = { scope.launch { pager.animateScrollToPage(pager.currentPage + 1) } }) { Icon(Icons.Outlined.ArrowForward, "Next page") }
                        if (pager.currentPage == pages.count - 1) TextButton(onClick = { finished = !finished; ReadingProgress(prefs, id).setFinished(finished) }) { Text(if (finished) "Finished · Undo" else "Finish") }
                    } }) { padding ->
                    HorizontalPager(pager, Modifier.fillMaxSize().padding(padding), beyondViewportPageCount = 0) { index ->
                        var bitmap by remember(index) { mutableStateOf<Bitmap?>(null) }; var failure by remember(index) { mutableStateOf<String?>(null) }
                        var scale by remember(index) { mutableFloatStateOf(1f) }; var offset by remember(index) { mutableStateOf(Offset.Zero) }
                        LaunchedEffect(index) { try { bitmap = withContext(Dispatchers.IO) { pages.render(index) } } catch (e: Exception) { failure = e.message } }
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            bitmap?.let { image -> Image(image.asImageBitmap(), "Page ${index + 1}", contentScale = ContentScale.Fit,
                                modifier = Modifier.fillMaxSize().pointerInput(Unit) {
                                    awaitEachGesture {
                                        awaitFirstDown(requireUnconsumed = false)
                                        do {
                                            val event = awaitPointerEvent(); val zoom = event.calculateZoom()
                                            if (scale > 1f || kotlin.math.abs(zoom - 1f) > .001f) {
                                                scale = (scale * zoom).coerceIn(1f, 4f)
                                                offset = if (scale == 1f) Offset.Zero else offset + event.calculatePan()
                                                event.changes.forEach { it.consume() }
                                            }
                                        } while (event.changes.any { it.pressed })
                                    }
                                }.graphicsLayer { scaleX = scale; scaleY = scale; translationX = offset.x; translationY = offset.y }) }
                            if (bitmap == null) { if (failure != null) Text(failure!!) else CircularProgressIndicator() }
                        }
                    }
                }
                if (picker) ModalBottomSheet(onDismissRequest = { picker = false }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
                    Text("Pages", Modifier.padding(24.dp), style = MaterialTheme.typography.headlineSmall)
                    LazyVerticalGrid(GridCells.Adaptive(72.dp), Modifier.heightIn(max = 440.dp).padding(horizontal = 24.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
                        items(pages.count) { index -> TextButton(onClick = { scope.launch { pager.scrollToPage(index); picker = false } }, modifier = Modifier.heightIn(min = 56.dp)) { Text("${index + 1}") } }
                    }
                }
            }
        } }
    }
}
