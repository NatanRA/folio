package com.natan.mediaportal

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Sort
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val Background: Color @Composable get() = MaterialTheme.colorScheme.background
private val Panel: Color @Composable get() = MaterialTheme.colorScheme.surfaceContainer
private val Accent: Color @Composable get() = MaterialTheme.colorScheme.primary
private val Muted: Color @Composable get() = MaterialTheme.colorScheme.onSurfaceVariant

class MainActivity : ComponentActivity() {
    private lateinit var store: LibraryStore
    private lateinit var server: BookServer
    private var local by mutableStateOf(emptyList<Title>())
    private var remote by mutableStateOf(emptyList<Title>())
    private var selected by mutableStateOf<Title?>(null)
    private var homeReturn by mutableIntStateOf(0)
    private val openReader = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            selected = null
            homeReturn++
        }
    }
    private var busy by mutableStateOf<String?>(null)
    private var error by mutableStateOf<String?>(null)
    /** A scanned pairing link waiting for the reader to confirm it. */
    private var pendingPairing by mutableStateOf<PairingLink?>(null)
    private var pendingNavigation by mutableStateOf<FolioNavigation?>(null)
    private var connected by mutableStateOf(false)
    private var progressVersion by mutableIntStateOf(0)
    private var audioImport = false
    private var linkTo: String? = null
    private var pendingImport by mutableStateOf<Boolean?>(null)
    private var artworkMenu by mutableStateOf<Title?>(null)
    private var artworkTarget: String? = null
    private var artworkRevision by mutableIntStateOf(0)
    private val pickArtwork = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val id = artworkTarget
        if (uri != null && id != null) lifecycleScope.launch {
            busy = "Saving artwork…"
            try { Artwork.upload(this@MainActivity, id, uri); artworkRevision++ }
            catch (e: Exception) { error = e.message ?: "Couldn’t open that image." }
            finally { busy = null }
        }
    }
    private val importFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) lifecycleScope.launch {
            busy = "Importing file…"
            try { selected = store.import(uri, audioImport, linkTo, remote.find { it.id == linkTo }); local = store.all() }
            catch (_: Exception) { error = "Couldn’t load this file. Choose a supported, unprotected e-book or audiobook." }
            finally { busy = null }
        }
    }
    override fun onResume() { super.onResume(); if (::store.isInitialized) { local = store.all(); progressVersion++ }; PositionSync.schedule(this, immediate = true) }
    /** Leaving the app is the last chance to hand this device's listening position over. */
    override fun onStop() { super.onStop(); PositionSync.schedule(this, immediate = true) }
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("artworkTarget", artworkTarget)
        outState.putString("importLink", linkTo)
        outState.putBoolean("audioImport", audioImport)
    }
    private fun percent(title: Title): Int { progressVersion; return getSharedPreferences("progress", MODE_PRIVATE).getInt("ebookPercent:${title.id}", 0) }
    /**
     * How far through a title the reader is, whether they were reading it or listening to it.
     * `percent` covers only the e-book, so on its own it hides every audiobook from Home. A book
     * that has been listened to but whose length is not yet known counts as barely started.
     */
    private fun progress(title: Title): Int {
        val prefs = getSharedPreferences("progress", MODE_PRIVATE)
        val position = prefs.getLong("audio:${title.id}", 0)
        val duration = prefs.getLong("audioDuration:${title.id}", 0)
        val listened = when {
            position <= 0L -> 0
            duration > 0L -> ((position * 100) / duration).toInt().coerceIn(1, 99)
            else -> 1
        }
        return maxOf(percent(title), listened)
    }
    private fun refresh(account: ServerAccount? = server.account()) {
        if (account == null || busy != null) return
        lifecycleScope.launch {
            busy = "Connecting to your library…"
            try { val books = server.refresh(account); server.save(account); remote = books; connected = true }
            catch (e: Exception) { error = if (e is IllegalArgumentException || e is IllegalStateException) e.message else "Can’t reach your server. Check the address and connection. Downloaded books are still available." }
            finally { busy = null }
        }
    }
    private fun download(title: Title) {
        if (busy != null) return
        lifecycleScope.launch {
            busy = "Downloading ${title.name}…"
            try { selected = server.download(title, store); local = store.all() }
            catch (e: Exception) { error = if (e is IllegalStateException || e is IllegalArgumentException) e.message else "Download failed. Check your connection and try again." }
            finally { busy = null }
        }
    }
    private fun downloadAudio(title: Title) {
        if (busy != null) return
        lifecycleScope.launch {
            busy = "Downloading ${title.name}…"
            try { selected = server.downloadAudio(title, store); local = store.all() }
            catch (e: Exception) { error = if (e is IllegalStateException || e is IllegalArgumentException) e.message else "Download failed. Check your connection and try again." }
            finally { busy = null }
        }
    }
    private fun read(title: Title) { openReader.launch(Intent(this, if (title.ebook?.substringAfterLast('.')?.lowercase() in setOf("pdf", "cbz")) FixedReaderActivity::class.java else ReaderActivity::class.java).putExtra("titleId", title.id)) }
    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        FolioNavigation.from(intent.data?.toString())?.let { pendingNavigation = it; return }
        PairingLink.from(intent.data?.toString())?.let { pendingPairing = it }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingPairing = PairingLink.from(intent?.data?.toString())
        pendingNavigation = FolioNavigation.from(intent?.data?.toString())
        artworkTarget = savedInstanceState?.getString("artworkTarget")
        linkTo = savedInstanceState?.getString("importLink")
        audioImport = savedInstanceState?.getBoolean("audioImport") ?: false
        androidx.core.view.WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = false
        store = LibraryStore(this); server = BookServer(this)
        local = store.all(); remote = server.cached(); connected = server.account() != null
        setContent {
            PortalTheme {
                val telegram = remember { TelegramLibrary.get(this@MainActivity) }
                val telegramRevision by telegram.revision.collectAsState()
                LaunchedEffect(telegramRevision) { local = store.all() }
                val positionRevision by PositionSync.revision.collectAsState()
                LaunchedEffect(positionRevision) { progressVersion++ }
                LaunchedEffect(Unit) { if (telegram.configured) telegram.start() }
                var appearanceOpen by remember { mutableStateOf(false) }
                if (appearanceOpen) AppearanceSheet { appearanceOpen = false }
                var tab by remember(homeReturn) { mutableIntStateOf(0) }
                var query by remember { mutableStateOf("") }
                var searching by remember { mutableStateOf(false) }
                var freeBooks by remember { mutableStateOf(false) }
                val focus = LocalFocusManager.current
                val searchFocus = remember { androidx.compose.ui.focus.FocusRequester() }
                var addMenu by remember { mutableStateOf(false) }
                val libraryPrefs = remember { getSharedPreferences("library", MODE_PRIVATE) }
                var sort by remember { mutableStateOf(LibrarySort.entries.find { it.name == libraryPrefs.getString("sort", null) } ?: LibrarySort.Title) }
                var sortMenu by remember { mutableStateOf(false) }
                fun closeSearch() { searching = false; query = ""; freeBooks = false; focus.clearFocus() }
                var listening by remember(homeReturn) { mutableStateOf<Title?>(if (homeReturn == 0 && intent.getBooleanExtra("openAudio", false)) local.find { it.id == getSharedPreferences("progress", MODE_PRIVATE).getString("activeAudio", null) } else null) }
                LaunchedEffect(pendingNavigation) {
                    val destination = pendingNavigation ?: return@LaunchedEffect
                    selected = null
                    listening = null
                    appearanceOpen = false
                    addMenu = false
                    closeSearch()
                    tab = destination.tab
                    if (destination.query != null) {
                        query = destination.query
                        searching = true
                    }
                    pendingNavigation = null
                }
                val titles = remote.map { book -> local.find { it.id == book.id } ?: book } + local.filter { book -> remote.none { it.id == book.id } }
                BackHandler(searching || selected != null || listening != null || tab != 0) { if (searching) closeSearch() else if (listening != null) listening = null else if (selected != null) selected = null else tab = 0 }
                val hazeState = remember { HazeState() }
                val tabItems = remember { listOf(TabItem("Home", Icons.Outlined.Home), TabItem("Library", Icons.Outlined.AutoStories), TabItem("Downloads", Icons.Outlined.Download), TabItem("Server", Icons.Outlined.Dns)) }
                Scaffold(containerColor = Background) { padding ->
                    Column(Modifier.fillMaxSize().padding(padding)) {
                        Row(Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(painterResource(R.drawable.portal_logo), "Folio",
                                Modifier.size(28.dp), tint = Accent)
                            Spacer(Modifier.weight(1f))
                            // The toolbar icons slide off to the right while the search field grows out of the magnifier.
                            AnimatedVisibility(!searching, enter = slideInHorizontally(tween(320, easing = FastOutSlowInEasing)) { it } + fadeIn(tween(200)), exit = slideOutHorizontally(tween(320, easing = FastOutSlowInEasing)) { it } + fadeOut(tween(160))) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    if (selected == null && listening == null && tab in 1..2) Box {
                                        IconButton(onClick = { sortMenu = true }) { Icon(Icons.AutoMirrored.Outlined.Sort, "Sort library", tint = Accent) }
                                        DropdownMenu(sortMenu, { sortMenu = false }) {
                                            LibrarySort.entries.forEach { option ->
                                                DropdownMenuItem(text = { Text(option.label) }, onClick = { sort = option; libraryPrefs.edit().putString("sort", option.name).apply(); sortMenu = false },
                                                    trailingIcon = { if (sort == option) Icon(Icons.Outlined.Check, "Selected") })
                                            }
                                        }
                                    }
                                    IconButton(onClick = { searching = true }) { Icon(Icons.Outlined.Search, "Search", tint = Accent) }
                                    IconButton(onClick = { appearanceOpen = true }) { Icon(Icons.Outlined.Palette, "Appearance", tint = Accent) }
                                    if (selected == null) IconButton(onClick = { refresh() }, enabled = connected && busy == null) { Icon(Icons.Outlined.Refresh, "Refresh library", tint = if (connected) Accent else Muted.copy(alpha = .4f)) }
                                    IconButton(onClick = { addMenu = true }, enabled = busy == null) { Icon(Icons.Outlined.Add, "Import file", tint = Accent) }
                                }
                            }
                        }
                        LaunchedEffect(searching) { if (searching) { delay(60); runCatching { searchFocus.requestFocus() } } }
                        busy?.let { Text(it, Modifier.padding(horizontal = 20.dp, vertical = 8.dp), color = Accent, maxLines = 2); LinearProgressIndicator(Modifier.fillMaxWidth()) }
                        Box(Modifier.fillMaxSize()) {
                            val blur by androidx.compose.animation.core.animateDpAsState(if (searching) 26.dp else 0.dp, tween(260, easing = FastOutSlowInEasing), label = "Search blur")
                            Column(Modifier.fillMaxSize().hazeSource(hazeState).blur(blur)) {
                        // The activity fade already animates returning from the reader.
                        // Start directly at Home instead of layering a detail-exit animation.
                        key(homeReturn) { AnimatedContent(
                            targetState = Triple(tab, selected, listening),
                            contentKey = { (page, book, audio) -> audio?.let { "audio:${it.id}" } ?: book?.let { "book:${it.id}" } ?: "tab:$page" },
                            modifier = Modifier.fillMaxSize(), label = "Screen navigation",
                            transitionSpec = {
                                val tabs = initialState.second == null && initialState.third == null && targetState.second == null && targetState.third == null
                                fun depth(state: Triple<Int, Title?, Title?>) = when {
                                    state.third != null -> 2
                                    state.second != null -> 1
                                    else -> 0
                                }
                                val entering = depth(targetState) > depth(initialState)
                                if (tabs) {
                                    // Sibling destinations follow the order of the bottom bar.
                                    // Fade through instead of layering two dense libraries.
                                    val direction = if (targetState.first > initialState.first) 1 else -1
                                    (fadeIn(tween(160, 90)) + slideInHorizontally(tween(250, easing = FastOutSlowInEasing)) { direction * (it / 20) })
                                        .togetherWith(fadeOut(tween(90))).using(null)
                                } else if (entering) {
                                    // Details/player enter above their parent; the parent stays put.
                                    (fadeIn(tween(180, 60)) + slideInHorizontally(tween(300, easing = FastOutSlowInEasing)) { it / 8 })
                                        .togetherWith(fadeOut(tween(120))).apply { targetContentZIndex = 1f }.using(null)
                                } else {
                                    // Returning reveals the parent while the child leaves right.
                                    fadeIn(tween(200, 60)).togetherWith(fadeOut(tween(180)) +
                                        slideOutHorizontally(tween(260, easing = FastOutSlowInEasing)) { it / 8 })
                                        .apply { targetContentZIndex = -1f }.using(null)
                                }
                            }
                        ) { screen ->
                          Column(Modifier.fillMaxSize()) {
                          when {
                            screen.third != null -> AudioPlayer(screen.third!!) { listening = null }
                            screen.second != null -> {
                                val title = local.find { it.id == screen.second!!.id } ?: screen.second!!
                                Detail(title, onListen = { listening = title })
                            }
                            screen.first == 3 -> ServerSettings { refresh(it) }
                            screen.first == 0 -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 100.dp)) {
                                val resume = local.filter { progress(it) in 1..99 }
                                    .sortedByDescending { getSharedPreferences("progress", MODE_PRIVATE).getLong("lastOpened:${it.id}", 0) }
                                val featured = resume.firstOrNull() ?: titles.firstOrNull()
                                item {
                                    if (featured != null) Featured(featured, resume.isNotEmpty()) { selected = featured }
                                    else Column(Modifier.padding(24.dp)) {
                                        Icon(Icons.Outlined.AutoStories, null, Modifier.size(48.dp), tint = Accent)
                                        Text("No books yet", fontSize = 28.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(vertical = 16.dp))
                                        Text("Connect BookLore to browse your covers and choose what to take offline.", color = Muted)
                                        Button(colors = ButtonDefaults.buttonColors(), onClick = { tab = 3 }, modifier = Modifier.padding(top = 24.dp).heightIn(min = 48.dp)) { Text("Connect your server") }
                                    }
                                }
                                if (resume.size > 1) item { Shelf("Continue", resume) }
                                if (titles.isNotEmpty()) item { Shelf(if (connected) "In your library" else "Recently added", titles) }
                                val downloads = local.filter { it.isLocal }
                                if (downloads.isNotEmpty() && downloads.size < titles.size) item { Shelf("Ready offline", downloads) }
                            }
                            else -> {
                                val source = if (screen.first == 2) local.filter { it.isLocal } else titles
                                Text(if (screen.first == 2) "Downloads" else "Library", Modifier.padding(20.dp, 12.dp), fontSize = 28.sp, fontWeight = FontWeight.Bold)
                                Text("${source.size} titles · ${if (screen.first == 2) "Available offline" else if (connected) "Your BookLore + local files" else "On this device"}", Modifier.padding(20.dp, 16.dp), color = Muted, fontSize = 12.sp)
                                val filtered = LibrarySorting.sorted(source.filter { "${it.name} ${it.author}".contains(query, true) }, sort)
                                if (filtered.isEmpty()) Column(Modifier.padding(24.dp)) {
                                    Text(if (query.isNotBlank()) "No matching titles" else if (screen.first == 2) "No downloads yet" else "No books yet", fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                                    Text(if (screen.first == 2) "Open a book in your library and download it to read without a connection." else "Connect your server or load an e-book using +.", Modifier.padding(top = 12.dp), color = Muted)
                                }
                                LazyVerticalGrid(GridCells.Adaptive(104.dp), contentPadding = PaddingValues(20.dp, 0.dp, 20.dp, 100.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
                                    items(filtered, key = { it.id }) { BookTile(it, Modifier.fillMaxWidth()) }
                                }
                            }
                        }
                          }
                        } }
                            }
                            // The floating tab bar sits over the page, so lists pad their bottom to scroll clear of it.
                            if (selected == null && listening == null) GlassTabBar(tabItems, tab, { tab = it; closeSearch() }, hazeState, Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp).blur(blur))
                            // Search floats over the content on a translucent, blurred layer so nothing behind it moves.
                            androidx.compose.animation.AnimatedVisibility(visible = searching, enter = fadeIn(tween(220)) + scaleIn(tween(280, easing = FastOutSlowInEasing), initialScale = .98f), exit = fadeOut(tween(170)) + scaleOut(tween(170), targetScale = .98f)) {
                                Column(Modifier.fillMaxSize().background(Background.copy(alpha = .55f)).clickable(indication = null, interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }) { closeSearch() }) {
                        OutlinedTextField(query, { query = it }, placeholder = { Text("Search books or authors") }, leadingIcon = { Icon(Icons.Outlined.Search, null) },
                            trailingIcon = { IconButton(onClick = { closeSearch() }) { Icon(Icons.Outlined.Close, "Close search") } },
                            singleLine = true, shape = RoundedCornerShape(28.dp), modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).testTag("global-search").focusRequester(searchFocus))
                            Row(Modifier.padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                FilterChip(!freeBooks, { freeBooks = false }, label = { Text("My library") })
                                FilterChip(freeBooks, { freeBooks = true; focus.clearFocus() }, label = { Text("Free books") })
                            }
                            if (freeBooks) DiscoverScreen(query) { local = store.all() }
                            else LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false), contentPadding = PaddingValues(16.dp)) {
                                val matches = titles.filter { "${it.name} ${it.author}".contains(query.trim(), true) }
                                if (matches.isEmpty()) item { Text("No matching titles in your library. Try Free books.", Modifier.padding(12.dp)) }
                                items(matches, key = { it.id }) { book ->
                                    ListItem(headlineContent = { Text(book.name) }, supportingContent = { Text(book.author) }, leadingContent = { Icon(Icons.Outlined.AutoStories, null) }, colors = ListItemDefaults.colors(containerColor = Color.Transparent), modifier = Modifier.clickable { selected = book; listening = null; closeSearch() })
                                }
                            }
                                }
                            }
                        }
                    }
                }
                if (addMenu) AlertDialog(onDismissRequest = { addMenu = false }, title = { Text("Add to your library") }, text = {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(colors = ButtonDefaults.buttonColors(), onClick = { addMenu = false; choose(false, null) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Load e-book") }
                        OutlinedButton(onClick = { addMenu = false; choose(true, null) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Load audiobook") }
                    }
                }, confirmButton = { TextButton(onClick = { addMenu = false }) { Text("Cancel") } })
                pendingPairing?.let { pairing -> AlertDialog(onDismissRequest = { pendingPairing = null }, title = { Text("Connect this library?") }, text = {
                    Column {
                        Text(pairing.address)
                        Text("Signs in as ${pairing.user}", color = Muted, modifier = Modifier.padding(top = 4.dp))
                        Text("Connect only if you made this code yourself. It carries the password for this account, so treat the code the way you would treat the password.",
                            fontSize = 12.sp, color = Muted, modifier = Modifier.padding(top = 20.dp))
                    }
                }, confirmButton = { TextButton(onClick = {
                    val target = pairing
                    pendingPairing = null
                    runCatching { refresh(ServerAccount(Opds.endpoint(target.address), target.user, target.password)) }
                        .onFailure { error = it.message ?: "Check the server address." }
                }) { Text("Connect library") } }, dismissButton = { TextButton(onClick = { pendingPairing = null }) { Text("Not now") } }) }
                pendingImport?.let { audio -> AlertDialog(onDismissRequest = { pendingImport = null }, title = { Text(if (audio) "Load audiobook" else "Load e-book") }, text = {
                    Column {
                        Text("Choose a file from your device. A copy will be saved for offline ${if (audio) "listening" else "reading"}.")
                        Text(if (audio) "Supported files: MP3, M4A, M4B, AAC, FLAC, OGG and WAV. Unprotected files only. Chapter availability depends on the file’s metadata." else "Supported files: EPUB, PDF and CBZ comics. Unprotected files only.", fontSize = 12.sp, color = Muted, modifier = Modifier.padding(top = 24.dp))
                    }
                }, confirmButton = { TextButton(onClick = { pendingImport = null; importFile.launch(if (audio) arrayOf("audio/*", "application/mp4", "application/octet-stream") else arrayOf("application/epub+zip", "application/pdf", "application/vnd.comicbook+zip", "application/x-cbz", "application/zip", "application/octet-stream")) }) { Text("Choose file") } }, dismissButton = { TextButton(onClick = { pendingImport = null }) { Text("Cancel") } }) }
                artworkMenu?.let { title -> AlertDialog(onDismissRequest = { artworkMenu = null }, title = { Text("Edit artwork") }, text = {
                    Column {
                        Text("Choose a cover image, or recover artwork embedded in this title’s files.")
                        Text("Supported images: JPG, PNG and WebP · up to 20 MB. Your chosen artwork is saved on this device.", fontSize = 12.sp, color = Muted, modifier = Modifier.padding(top = 20.dp))
                    }
                }, confirmButton = { TextButton(onClick = { artworkMenu = null; artworkTarget = title.id; pickArtwork.launch(arrayOf("image/jpeg", "image/png", "image/webp")) }) { Text("Choose image") } }, dismissButton = { TextButton(onClick = {
                    artworkMenu = null
                    lifecycleScope.launch {
                        busy = "Recovering artwork…"
                        try { if (Artwork.restore(this@MainActivity, title)) artworkRevision++ else error = "No embedded artwork was found. Choose an image instead." }
                        catch (_: Exception) { error = "Couldn’t recover artwork. Choose an image instead." }
                        finally { busy = null }
                    }
                }, enabled = title.audio != null || title.ebook != null) { Text("Recover from file") } }) }
                error?.let { AlertDialog(onDismissRequest = { error = null }, title = { Text("Something needs attention") }, text = { Text(it) }, confirmButton = { TextButton(onClick = { error = null }) { Text("OK") } }) }
            }
        }
        if (connected) refresh()
    }
    @Composable private fun Featured(title: Title, continuing: Boolean, click: () -> Unit) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).clip(RoundedCornerShape(28.dp)).background(Panel).clickable(onClick = click).padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
            Cover(title, Modifier.width(116.dp).aspectRatio(.67f), server, artworkRevision)
            Column(Modifier.weight(1f).padding(start = 20.dp)) {
                Text(if (continuing) "PICK UP WHERE YOU LEFT OFF" else "FROM YOUR COLLECTION", color = Accent, fontSize = 10.sp, letterSpacing = 1.sp)
                Text(title.name, fontSize = 22.sp, lineHeight = 27.sp, maxLines = 4, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Bold, modifier = Modifier.padding(vertical = 10.dp))
                Text(title.author, color = Muted, fontSize = 13.sp, maxLines = 2)
                Text(if (continuing) "Continue · ${progress(title)}%  →" else "View book  →", color = Accent, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 16.dp))
            }
        }
    }
    @Composable private fun Shelf(label: String, books: List<Title>) {
        Text(label, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 20.dp, top = 24.dp, end = 20.dp, bottom = 14.dp))
        LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) { items(books, key = { it.id }) { BookTile(it, Modifier.width(118.dp)) } }
    }
    @Composable private fun BookTile(title: Title, modifier: Modifier) {
        Column(modifier.clickable { selected = title }) {
            Box {
                Cover(title, Modifier.fillMaxWidth().aspectRatio(.67f), server, artworkRevision)
                AvailabilityBadges(title.isLocal, remote.any { it.id == title.id } || title.telegramSource != null, Modifier.align(Alignment.BottomEnd).padding(5.dp))
            }
            if (progress(title) > 0) LinearProgressIndicator(progress = { progress(title) / 100f }, modifier = Modifier.fillMaxWidth().height(3.dp), trackColor = Panel, gapSize = 0.dp, drawStopIndicator = {})
            Text(title.name, maxLines = 2, overflow = TextOverflow.Ellipsis, fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 8.dp))
            Text(title.author.ifEmpty { if (title.audio != null) "Audiobook" else "Ebook" }, color = Muted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
    @Composable private fun Detail(title: Title, onListen: () -> Unit) {
        val prefs = remember { getSharedPreferences("progress", MODE_PRIVATE) }
        val audioPosition = prefs.getLong("audio:${title.id}", 0).coerceAtLeast(0)
        val audioDuration by produceState(0L, title.audio) {
            value = withContext(Dispatchers.IO) {
                title.audio?.let { filename ->
                    runCatching {
                        val metadata = android.media.MediaMetadataRetriever()
                        try { metadata.setDataSource(store.file(filename).path); metadata.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L }
                        finally { metadata.release() }
                    }.getOrDefault(0L)
                } ?: 0L
            }
        }
        BoxWithConstraints(Modifier.fillMaxSize()) {
        val coverWidth = if (maxHeight < 650.dp) 124.dp else 170.dp
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp)) {
            Box(Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(Panel, Background))).padding(20.dp), contentAlignment = Alignment.Center) { Cover(title, Modifier.width(coverWidth).aspectRatio(.67f), server, artworkRevision) }
            TextButton(onClick = { artworkMenu = title }, enabled = busy == null, modifier = Modifier.align(Alignment.CenterHorizontally)) { Icon(Icons.Outlined.Image, null); Spacer(Modifier.width(8.dp)); Text("Edit artwork") }
            Text(title.name, fontSize = 28.sp, lineHeight = 34.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 20.dp))
            Text(title.author, color = Muted, modifier = Modifier.padding(top = 8.dp, bottom = 20.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (title.ebook != null || title.download != null) FormatButton(
                    "E-book", Icons.Outlined.MenuBook,
                    if (title.ebook == null) "Download" else if (percent(title) == 100) "Finished · 100%" else "${percent(title)}% read",
                    if (title.ebook != null) percent(title) / 100f else null,
                    Modifier.weight(1f).testTag("ebook-format"),
                    enabled = title.ebook != null || (connected && busy == null)
                ) { if (title.ebook != null) read(title) else download(title) }
                if (title.audio != null || title.audioDownload != null) FormatButton(
                    "Audiobook", Icons.Outlined.Headphones,
                    if (title.audio == null) "Download" else "${audioTime(audioPosition)} listened",
                    if (title.audio != null && audioDuration > 0) (audioPosition.toFloat() / audioDuration).coerceIn(0f, 1f) else null,
                    Modifier.weight(1f).testTag("audio-format"),
                    enabled = title.audio != null || (connected && busy == null)
                ) { if (title.audio != null) onListen() else downloadAudio(title) }
            }
            if (title.audio == null && title.audioDownload == null) TextButton(onClick = { attachTo(title, true) }, enabled = busy == null, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("+ Add audiobook") }
            if (title.streaming) {
                val telegram = remember { TelegramLibrary.get(this@MainActivity) }
                val transfer by telegram.activity.collectAsState()
                TextButton(onClick = { lifecycleScope.launch {
                    try {
                        val entry = telegram.resolve(Uri.parse(title.audio))
                        val future = androidx.media3.session.MediaController.Builder(this@MainActivity, androidx.media3.session.SessionToken(this@MainActivity, android.content.ComponentName(this@MainActivity, PlaybackService::class.java))).buildAsync()
                        val controller = withContext(Dispatchers.IO) { future.get(15, java.util.concurrent.TimeUnit.SECONDS) }
                        controller.stop(); controller.release()
                        telegram.download(entry) { local = store.all(); selected = local.find { it.id == title.id } }
                    }
                    catch (e: Exception) { error = e.message }
                } }, enabled = transfer == null) { Text(transfer ?: "Keep offline") }
            }
            if (title.ebook == null && title.download == null) TextButton(onClick = { attachTo(title, false) }, enabled = busy == null, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("+ Add e-book") }
            if (title.ebook != null && percent(title) == 100) TextButton(onClick = {
                val progress = ReadingProgress(getSharedPreferences("progress", MODE_PRIVATE), title.id)
                progress.setFinished(false)
                progressVersion++
            }, modifier = Modifier.heightIn(min = 48.dp)) {
                Text("Mark as unfinished")
            }
            if (title.description.isNotBlank()) { Text("About this book", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 24.dp, bottom = 12.dp)); Text(android.text.Html.fromHtml(title.description, android.text.Html.FROM_HTML_MODE_LEGACY).toString(), color = Muted, lineHeight = 24.sp) }
        }
        }
    }
    private fun attachTo(title: Title, audio: Boolean) {
        choose(audio, title.id)
    }
    @Composable private fun ServerSettings(connect: (ServerAccount) -> Unit) {
        val saved = remember(connected) { server.account() }
        var url by remember(saved) { mutableStateOf(saved?.url ?: "") }
        var user by remember(saved) { mutableStateOf(saved?.user ?: "") }
        var password by remember(saved) { mutableStateOf(saved?.password ?: "") }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp).padding(bottom = 80.dp)) {
            Text("Server settings", fontSize = 28.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 24.dp))
            SettingsSection("BookLore", if (connected) "Connected · ${remote.size} titles" else "Not connected", Icons.Outlined.Dns) {
            Text("Browse your books here. Download favourites to keep reading anywhere.", color = Muted, modifier = Modifier.padding(bottom = 20.dp))
            OutlinedTextField(url, { url = it }, label = { Text("Server address") }, placeholder = { Text("https://your-server") }, singleLine = true, modifier = Modifier.fillMaxWidth(), enabled = busy == null)
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(user, { user = it }, label = { Text("OPDS username") }, singleLine = true, modifier = Modifier.fillMaxWidth(), enabled = busy == null)
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(password, { password = it }, label = { Text("OPDS password") }, visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = Modifier.fillMaxWidth(), enabled = busy == null)
            Text("Use the OPDS login you created in BookLore, which may differ from your web login. Your password is stored encrypted on this device.", fontSize = 12.sp, color = Muted, modifier = Modifier.padding(vertical = 16.dp))
            Button(colors = ButtonDefaults.buttonColors(), onClick = { try { require(!user.contains(':')) { "Username cannot contain a colon." }; connect(ServerAccount(Opds.endpoint(url), user.trim(), password)) } catch (e: Exception) { error = e.message ?: "Check the server address." } }, enabled = busy == null && url.isNotBlank() && user.isNotBlank() && password.isNotBlank(), modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text(if (connected) "Save & refresh library" else "Connect library") }
            if (connected) TextButton(onClick = { server.disconnect(); connected = false; remote = emptyList() }, enabled = busy == null) { Text("Disconnect server") }
            }
            TelegramSettings { local = store.all() }
            KoSyncSettings()
            NotesSyncSettings()
            HorizontalDivider(color = Panel)
            Text("Downloaded books remain available offline.", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 20.dp))
        }
    }
    private fun choose(audio: Boolean, title: String?) { audioImport = audio; linkTo = title; pendingImport = audio }
    @Composable private fun AudioPlayer(title: Title, close: () -> Unit) {
        var controller by remember(title.id) { mutableStateOf<androidx.media3.session.MediaController?>(null) }
        var connectionError by remember(title.id) { mutableStateOf(false) }
        DisposableEffect(title.id) {
            val token = androidx.media3.session.SessionToken(this@MainActivity, android.content.ComponentName(this@MainActivity, PlaybackService::class.java))
            val future = androidx.media3.session.MediaController.Builder(this@MainActivity, token).buildAsync()
            var active = true
            future.addListener({
                if (active) try {
                    val connectedPlayer = future.get()
                    if (connectedPlayer.currentMediaItem?.mediaId != title.id) {
                        val savedPosition = getSharedPreferences("progress", MODE_PRIVATE).getLong("audio:${title.id}", 0)
                        connectedPlayer.setMediaItem(MediaItem.Builder().setMediaId(title.id).build(), savedPosition)
                        connectedPlayer.prepare()
                    }
                    controller = connectedPlayer
                } catch (_: Exception) { connectionError = true }
            }, androidx.core.content.ContextCompat.getMainExecutor(this@MainActivity))
            onDispose { active = false; androidx.media3.session.MediaController.releaseFuture(future) }
        }
        val player = controller
        if (player == null) {
            Column(Modifier.padding(24.dp)) {
                Text(if (connectionError) "Couldn’t connect to the audio player. Open the audiobook again to retry." else "Opening audio player…")
                TextButton(onClick = close) { Text("Back to book") }
            }
        } else AudioPlayerControls(title, player, close)
    }
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable private fun AudioPlayerControls(title: Title, player: androidx.media3.common.Player, close: () -> Unit) {
        val prefs = remember { getSharedPreferences("progress", MODE_PRIVATE) }
        var position by remember { mutableLongStateOf(player.bookPosition) }; var duration by remember { mutableLongStateOf(0L) }; var playing by remember { mutableStateOf(false) }
        var playbackError by remember { mutableStateOf<String?>(null) }
        var speed by remember(player) { mutableFloatStateOf(player.playbackParameters.speed) }
        var speedOpen by remember { mutableStateOf(false) }
        if (speedOpen) ModalBottomSheet(onDismissRequest = { speedOpen = false }) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 24.dp)) {
                Text("Playback speed", style = MaterialTheme.typography.headlineSmall)
                Text("Applies to all audiobooks. Voices keep their natural pitch.", color = Muted, modifier = Modifier.padding(vertical = 12.dp))
                listOf(listOf(.5f, .75f, 1f), listOf(1.25f, 1.5f, 1.75f), listOf(2f, 2.5f, 3f)).forEach { row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { value ->
                            FilterChip(selected = kotlin.math.abs(speed - value) < .01f,
                                onClick = { player.setPlaybackSpeed(value); speedOpen = false },
                                label = { Text("${value}×") }, modifier = Modifier.weight(1f).heightIn(min = 48.dp))
                        }
                    }
                }
                TextButton(onClick = { speedOpen = false }, modifier = Modifier.align(Alignment.End)) { Text("Done") }
            }
        }
        var scrubbing by remember(title.id) { mutableStateOf(false) }
        var snappedChapter by remember(title.id) { mutableStateOf<Long?>(null) }
        val hapticView = LocalView.current
        fun chapterTick() {
            hapticView.performHapticFeedback(if (android.os.Build.VERSION.SDK_INT >= 34) android.view.HapticFeedbackConstants.SEGMENT_TICK else android.view.HapticFeedbackConstants.CLOCK_TICK)
        }
        DisposableEffect(player) {
            val listener = object : androidx.media3.common.Player.Listener {
                override fun onPlaybackStateChanged(state: Int) { duration = player.bookDuration; if (title.streaming && state == androidx.media3.common.Player.STATE_READY) artworkRevision++ }
                override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) { duration = player.bookDuration }
                override fun onIsPlayingChanged(isPlaying: Boolean) { playing = isPlaying }
                override fun onPlaybackParametersChanged(parameters: androidx.media3.common.PlaybackParameters) { speed = parameters.speed }
                override fun onPlayerError(error: androidx.media3.common.PlaybackException) { playbackError = if (title.streaming) "Couldn’t buffer audio. Check your connection and Telegram sign-in, then retry." else "This audio could not be played. The file may be damaged or use an unsupported codec." }
            }
            player.addListener(listener)
            onDispose { player.removeListener(listener) }
        }
        LaunchedEffect(player) { while (true) { if (!scrubbing) position = player.bookPosition; duration = player.bookDuration; playing = player.isPlaying; delay(1000) } }
        val localChapters by produceState<List<AudioChapter>?>(null, title.audio) { value = if (title.streaming) emptyList() else withContext(Dispatchers.IO) { AudioChapters.read(store.file(title.audio!!)) } }
        val chapters = if (title.streaming) (0 until player.mediaItemCount).map { index -> player.getMediaItemAt(index).let { AudioChapter(it.mediaMetadata.artist?.toString().orEmpty(), it.chapterStartMs) } } else localChapters
        val available = chapters.orEmpty().filter { duration == 0L || it.startMs < duration }
        val currentChapter = available.indexOfLast { it.startMs <= position }
        Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = close, modifier = Modifier.padding(start = 4.dp)) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back to book") }
            Text("Audiobook", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(52.dp))
        }
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 20.dp).testTag("audio-content"), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.fillMaxWidth().padding(top = 12.dp), contentAlignment = Alignment.Center) { Cover(title, Modifier.width(200.dp).aspectRatio(.69f).shadow(20.dp, RoundedCornerShape(16.dp), ambientColor = Color.Black.copy(alpha = .15f), spotColor = Color.Black.copy(alpha = .15f)), server, artworkRevision) }
            Text(title.name, fontSize = 24.sp, lineHeight = 30.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 24.dp))
            if (playbackError != null && title.streaming) TextButton(onClick = { playbackError = null; player.prepare(); player.play() }) { Text("Retry streaming") }
            var showChapters by remember(title.audio) { mutableStateOf(false) }
            if (available.isNotEmpty()) {
                TextButton(onClick = { showChapters = !showChapters }) { Text(if (showChapters) "Hide chapters" else "Chapters (${available.size})") }
                if (showChapters) available.forEachIndexed { index, chapter ->
                    Row(Modifier.fillMaxWidth().clickable(enabled = duration > 0 && playbackError == null) { chapterTick(); player.seekBook(chapter.startMs); position = chapter.startMs }.heightIn(min = 48.dp).padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("${index + 1}. ${chapter.displayName(index)}", color = if (index == currentChapter) Accent else MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
                        Text(audioTime(chapter.startMs), color = Muted, modifier = Modifier.padding(start = 12.dp))
                    }
                }
            } else Text(if (chapters == null) "Reading chapters…" else "No supported embedded chapters found in this file.", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 16.dp))
            playbackError?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 12.dp)) }
        }
        HorizontalDivider(color = Panel)
        Column(Modifier.fillMaxWidth().background(Background).padding(horizontal = 24.dp, vertical = 12.dp).testTag("audio-controls"), horizontalAlignment = Alignment.CenterHorizontally) {
        if (currentChapter >= 0) Text(available[currentChapter].displayName(currentChapter), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(bottom = 2.dp))
        Box(Modifier.fillMaxWidth()) {
            Slider(value = position.toFloat().coerceIn(0f, duration.toFloat().coerceAtLeast(1f)), onValueChange = {
                scrubbing = true
                val snap = snapAudioChapter(it.toLong(), duration, available)
                if (snap != null && snap.startMs != snappedChapter) chapterTick()
                snappedChapter = snap?.startMs
                position = snap?.startMs ?: it.toLong()
            }, onValueChangeFinished = {
                player.seekBook(position)
                prefs.edit().putLong("audio:${title.id}", position).putLong("audioModified:${title.id}", System.currentTimeMillis()).apply()
                scrubbing = false
                snappedChapter = null
            }, modifier = Modifier.testTag("audio-timeline"), valueRange = 0f..duration.toFloat().coerceAtLeast(1f), enabled = duration > 0 && playbackError == null, colors = SliderDefaults.colors(inactiveTrackColor = Panel),
                track = { state -> SliderDefaults.Track(sliderState = state, enabled = duration > 0 && playbackError == null,
                    colors = SliderDefaults.colors(inactiveTrackColor = Panel), thumbTrackGapSize = 0.dp, drawStopIndicator = null) })
            val markerColor = Accent
            Canvas(Modifier.matchParentSize().padding(horizontal = 10.dp)) {
                if (duration > 0) available.drop(1).forEach { chapter ->
                    val x = size.width * chapter.startMs.toFloat() / duration
                    drawLine(markerColor, androidx.compose.ui.geometry.Offset(x, size.height / 2 - 8.dp.toPx()), androidx.compose.ui.geometry.Offset(x, size.height / 2 + 8.dp.toPx()), strokeWidth = 2.dp.toPx())
                }
            }
        }
        Row(Modifier.fillMaxWidth()) {
            Text(audioTime(position), color = Muted, fontSize = 12.sp, modifier = Modifier.weight(1f))
            Text(if (duration > 0) audioTime(duration) else "Loading…", color = Muted, fontSize = 12.sp)
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            SleepTimer(prefs)
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { speedOpen = true }, enabled = player.isCommandAvailable(androidx.media3.common.Player.COMMAND_SET_SPEED_AND_PITCH),
                modifier = Modifier.testTag("playback-speed")) { Text("${speed.toString().removeSuffix(".0")}×", fontSize = 17.sp) }
        }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(40.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { player.seekBook((player.bookPosition - 15000).coerceAtLeast(0)) }, modifier = Modifier.size(56.dp)) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Outlined.Replay, "Back 15 seconds", Modifier.size(34.dp), tint = Accent)
                    Text("15", fontSize = 9.sp, fontWeight = FontWeight.Bold, color = Accent, modifier = Modifier.padding(top = 3.dp))
                }
            }
            FilledTonalIconButton(onClick = { if (player.isPlaying) player.pause() else player.play() }, modifier = Modifier.size(width = 72.dp, height = 60.dp), shape = CircleShape, enabled = playbackError == null && duration > 0,
                colors = IconButtonDefaults.filledTonalIconButtonColors(containerColor = Accent.copy(alpha = .18f), contentColor = Accent, disabledContainerColor = Panel, disabledContentColor = Muted)) {
                Icon(if (playing) Icons.Outlined.Pause else Icons.Outlined.PlayArrow, if (playing) "Pause" else "Play", modifier = Modifier.size(34.dp))
            }
            IconButton(onClick = { player.seekBook((player.bookPosition + 30000).coerceAtMost(duration)) }, modifier = Modifier.size(56.dp)) { Icon(Icons.Outlined.Forward30, "Forward 30 seconds", Modifier.size(34.dp), tint = Accent) }
        }
        }
        }
    }
}

private fun audioTime(milliseconds: Long): String {
    val seconds = milliseconds.coerceAtLeast(0) / 1000
    return if (seconds >= 3600) "%d:%02d:%02d".format(seconds / 3600, seconds / 60 % 60, seconds % 60)
    else "%d:%02d".format(seconds / 60, seconds % 60)
}

@Composable private fun FormatButton(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, subtitle: String, progress: Float?, modifier: Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    val onAccent = MaterialTheme.colorScheme.onPrimary
    Surface(onClick = onClick, enabled = enabled, modifier = modifier, shape = RoundedCornerShape(28.dp), color = if (enabled) Accent else Panel, contentColor = if (enabled) onAccent else Muted) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(icon, null, tint = if (enabled) onAccent else Muted, modifier = Modifier.size(20.dp))
                Text(label, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            }
            Text(subtitle, color = if (enabled) onAccent else Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 10.dp))
            if (progress != null) LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth().padding(top = 10.dp).height(3.dp), color = onAccent, trackColor = onAccent.copy(alpha = .25f), gapSize = 0.dp, drawStopIndicator = {})
        }
    }
}

@Composable internal fun Cover(title: Title, modifier: Modifier, server: BookServer, revision: Int = 0) {
    val context = LocalContext.current
    val telegramRevision by TelegramLibrary.get(context).revision.collectAsState()
    val artwork by produceState<android.graphics.Bitmap?>(null, title.id, title.ebook, title.audio, title.coverUrl, revision, telegramRevision) { value = withContext(Dispatchers.IO) {
        val custom = java.io.File(context.filesDir, "${title.id}-custom.jpg")
        val embedded = java.io.File(context.filesDir, "${title.id}.jpg")
        if (!custom.exists() && !embedded.exists() && title.audio != null && !title.streaming) Artwork.fromAudio(java.io.File(context.filesDir, title.audio), embedded)
        val file = custom.takeIf { it.exists() } ?: embedded.takeIf { it.exists() } ?: server.cover(title)
        file?.let { android.graphics.BitmapFactory.decodeFile(it.path, android.graphics.BitmapFactory.Options().apply { inSampleSize = 2 }) }
    } }
    val shape = RoundedCornerShape(5.dp)
    if (artwork != null) Image(artwork!!.asImageBitmap(), "Cover of ${title.name}", modifier.clip(shape).background(MaterialTheme.colorScheme.surfaceContainer), contentScale = ContentScale.Fit)
    else Box(modifier.clip(shape).background(Brush.linearGradient(listOf(Color(0xFF45525B), Color(0xFF262E35)))).padding(12.dp)) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
            Icon(Icons.Outlined.AutoStories, null, tint = Accent, modifier = Modifier.size(22.dp))
            Text(title.name, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 4, overflow = TextOverflow.Ellipsis)
            Text(title.author, color = Muted, fontSize = 10.sp, maxLines = 1)
        }
    }
}
