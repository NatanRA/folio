package com.natan.mediaportal

import android.os.Bundle
import android.widget.LinearLayout
import android.widget.Button
import android.widget.TextView
import android.view.View
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentContainerView
import androidx.lifecycle.lifecycleScope
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import org.readium.r2.navigator.epub.EpubNavigatorFactory
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.readium.r2.navigator.epub.EpubPreferences
import org.readium.r2.navigator.preferences.TextAlign
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.publication.services.positions

@OptIn(org.readium.r2.shared.ExperimentalReadiumApi::class)
class ReaderActivity : FragmentActivity() {
    private var publication: Publication? = null
    private var syncDialog by mutableStateOf<ReaderDialog?>(null)
    private var dictionaryWord by mutableStateOf<String?>(null)
    internal fun defineWord(word: String) { dictionaryWord = word }
    internal fun showSyncDialog(dialog: ReaderDialog) {
        if (!isFinishing && !isDestroyed) syncDialog = dialog
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        // Reopen from durable files after process recreation rather than restoring a stale navigator.
        super.onCreate(null)
        val id = intent.getStringExtra("titleId") ?: return finish()
        val title = LibraryStore(this).all().find { it.id == id } ?: return finish()
        val file = title.ebook ?: return finish()
        val prefs = getSharedPreferences("progress", MODE_PRIVATE)
        var appearance by mutableStateOf(ReaderAppearance.load(prefs))
        var activeNavigator: EpubNavigatorFragment? = null
        var selectionMode: android.view.ActionMode? = null
        var selection by mutableStateOf<org.readium.r2.navigator.Selection?>(null)
        var selectionBounds by mutableStateOf<android.graphics.RectF?>(null)
        var moreSelection by mutableStateOf<ReadingNote?>(null)
        val notesStore = ReadingNotes(this, id)
        val bookmarksStore = ReadingNotes(this, id, "reading-bookmarks")
        val wordsStore = ReadingNotes(this, id, "reading-words")
        var words by mutableStateOf(wordsStore.all())
        var showWords by mutableStateOf(false)
        fun saveWords(updated: List<ReadingNote>) {
            try { wordsStore.save(updated); words = updated }
            catch (error: Exception) { showSyncDialog(ReaderDialog("Could not save words", error.message)) }
        }
        var bookmarks by mutableStateOf(bookmarksStore.all())
        var exploreTab by mutableStateOf<Int?>(null)
        var notes by mutableStateOf(notesStore.all())
        var showNotes by mutableStateOf(false)
        var transferNotes by mutableStateOf(false)
        var editingNote by mutableStateOf<ReadingNote?>(null)
        val notesMutex = Mutex()
        suspend fun decorate() {
            activeNavigator?.applyDecorations(notes.map { org.readium.r2.navigator.Decoration(it.id, it.locator,
                org.readium.r2.navigator.Decoration.Style.Highlight(0xFFFFD16A.toInt())) }, "notes")
        }
        fun saveNotes(update: (List<ReadingNote>) -> List<ReadingNote>) {
            lifecycleScope.launch {
                try {
                    notesMutex.withLock {
                        val updated = update(notes)
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { notesStore.save(updated) }
                        notes = updated
                        editingNote = null
                        decorate()
                    }
                } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                catch (error: Exception) { showSyncDialog(ReaderDialog("Could not save notes", error.message)) }
            }
        }
        fun selectionAction(entry: ReadingNote, action: SelectionAction) {
            when (action) {
                SelectionAction.Highlight -> saveNotes { it + entry }
                SelectionAction.Note -> editingNote = entry
                SelectionAction.Copy -> getSystemService(android.content.ClipboardManager::class.java)
                    .setPrimaryClip(android.content.ClipData.newPlainText("Book text", entry.quote))
                SelectionAction.Define -> defineWord(entry.quote)
                SelectionAction.Save -> if (entry.quote.length <= 200 && words.none { it.quote.equals(entry.quote, true) }) saveWords(words + entry)
            }
        }
        var pinned = prefs.getBoolean("reader:buttons", false)
        val progress = ReadingProgress(prefs, id)
        fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(appearance.paper.page.toArgb()) }
        val overlay = android.widget.FrameLayout(this)
        overlay.addView(root, android.widget.FrameLayout.LayoutParams(-1, -1))
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        val headerRow = LinearLayout(this).apply {
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(dp(16), 0, dp(16), 0)
            minimumHeight = dp(44)
        }
        val header = ComposeView(this).apply { setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed) }
        val pageCounter = TextView(this).apply {
            tag = "reader-page-counter"
            text = "…"; textSize = 11f; setTextColor(appearance.paper.ink.toArgb())
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
            fontFeatureSettings = "tnum"
            setPadding(dp(16), 0, 0, 0)
            contentDescription = "Loading ebook page count"
            minHeight = dp(44)
            gravity = android.view.Gravity.CENTER
        }
        headerRow.addView(header, LinearLayout.LayoutParams(dp(48), dp(48)))
        headerRow.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        val syncButton = Button(this).apply {
            tag = "reader-sync"; text = "Sync"; isAllCaps = false; isEnabled = false
        }
        // ReaderSync owns this action's enabled/busy state; expose it through the menu.
        syncButton.visibility = View.GONE
        root.addView(syncButton)
        headerRow.addView(pageCounter, LinearLayout.LayoutParams(-2, -2))
        val container = FragmentContainerView(this).apply { this.id = View.generateViewId() }
        val fold = PageFoldLayout(this).apply { tag = "reader-fold"; addView(container, android.widget.FrameLayout.LayoutParams(-1, -1)) }
        val controls = LinearLayout(this).apply { tag = "reader-controls"; isBaselineAligned = false }
        fun pageButton(forward: Boolean, home: Boolean = false) = object : Button(this) {
            private val arrowPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                style = android.graphics.Paint.Style.STROKE
                strokeWidth = 2.8f
                strokeCap = android.graphics.Paint.Cap.ROUND
                strokeJoin = android.graphics.Paint.Join.ROUND
            }
            private val arrowPath = android.graphics.Path().apply {
                if (home) {
                    moveTo(-9f, -1f); lineTo(0f, -9f); lineTo(9f, -1f)
                    moveTo(-7f, -2f); lineTo(-7f, 9f); lineTo(-2.5f, 9f); lineTo(-2.5f, 3f)
                    lineTo(2.5f, 3f); lineTo(2.5f, 9f); lineTo(7f, 9f); lineTo(7f, -2f)
                } else {
                    moveTo(-9f, 0f); lineTo(9f, 0f)
                    moveTo(1f, -8f); lineTo(9f, 0f); lineTo(1f, 8f)
                }
            }
            override fun onDraw(canvas: android.graphics.Canvas) {
                super.onDraw(canvas)
                arrowPaint.color = currentTextColor
                canvas.save()
                canvas.translate(width / 2f, height / 2f)
                val density = resources.displayMetrics.density
                canvas.scale(if (forward) density else -density, density)
                canvas.drawPath(arrowPath, arrowPaint)
                canvas.restore()
            }
        }.apply { contentDescription = if (home) "Home" else if (forward) "Next page" else "Previous page" }
        val back = pageButton(true, home = true).apply {
            setOnClickListener {
                setResult(RESULT_OK)
                finish()
                @Suppress("DEPRECATION")
                overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
            }
        }
        val previous = pageButton(false)
        val next = pageButton(true)
        val completion = Button(this).apply {
            tag = "reader-finish"
            visibility = View.GONE
            isAllCaps = false
            text = if (progress.finished) "Finished · 100% · Undo" else "Mark as finished"
            setOnClickListener {
                progress.setFinished(!progress.finished)
                text = if (progress.finished) "Finished · 100% · Undo" else "Mark as finished"
            }
        }
        root.addView(headerRow)
        root.addView(fold, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(completion, LinearLayout.LayoutParams(-1, dp(48)))
        fun controlShape(index: Int, color: Int) = android.graphics.drawable.GradientDrawable().apply {
            setColor(color)
            val inner = dp(8).toFloat(); val outer = dp(28).toFloat()
            cornerRadii = when (index) {
                0 -> floatArrayOf(outer, outer, inner, inner, inner, inner, outer, outer)
                2 -> floatArrayOf(inner, inner, outer, outer, outer, outer, inner, inner)
                else -> FloatArray(8) { inner }
            }
        }
        listOf(back, previous, next).forEachIndexed { index, it ->
            it.stateListAnimator = null
            it.elevation = 0f
            it.translationZ = 0f
            it.isAllCaps = false
            it.textSize = 11f
            it.setPadding(0, 0, 0, 0)
            it.backgroundTintList = null
            controls.addView(it, LinearLayout.LayoutParams(dp(64), dp(48)).apply {
                if (index != 2) marginEnd = dp(4)
            })
        }
        val navigationSpace = View(this).apply { visibility = if (pinned) View.VISIBLE else View.GONE }
        root.addView(navigationSpace, LinearLayout.LayoutParams(-1, dp(56)))
        val footer = TextView(this).apply {
            tag = "reader-footer"; textSize = 10f; setTextColor(appearance.paper.ink.toArgb())
            gravity = android.view.Gravity.END or android.view.Gravity.CENTER_VERTICAL
            setPadding(dp(20), 0, dp(20), 0)
        }
        root.addView(footer, LinearLayout.LayoutParams(-1, dp(24)))
        controls.visibility = if (pinned) View.VISIBLE else View.GONE
        overlay.addView(controls, android.widget.FrameLayout.LayoutParams(-2, dp(56), android.view.Gravity.BOTTOM or android.view.Gravity.CENTER_HORIZONTAL))
        // The reader theme's surface, applied with the button colours once the header composes.
        var controlShellColor = android.graphics.Color.TRANSPARENT
        var bottomInset = 0
        fun updateControlShell() {
            controls.background = android.graphics.drawable.InsetDrawable(
                android.graphics.drawable.GradientDrawable().apply {
                    setColor(controlShellColor); cornerRadius = dp(32).toFloat()
                }, dp(4), 0, dp(4), bottomInset
            )
            // Replacing a background applies its drawable padding to the View.
            // Restore the layout padding on theme refresh as well as inset changes.
            controls.setPadding(dp(8), dp(4), dp(8), bottomInset + dp(4))
        }
        ViewCompat.setOnApplyWindowInsetsListener(overlay) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            (controls.layoutParams as android.widget.FrameLayout.LayoutParams).apply {
                bottomMargin = 0; leftMargin = bars.left; rightMargin = bars.right
                height = dp(56) + bars.bottom
            }.also { controls.layoutParams = it }
            bottomInset = bars.bottom
            updateControlShell()
            insets
        }
        fun toggleControls() {
            controls.visibility = if (controls.visibility == View.VISIBLE && !pinned) View.GONE else View.VISIBLE
        }
        fun applyPaper(paper: ReaderPaper) {
            root.setBackgroundColor(paper.page.toArgb())
            pageCounter.setTextColor(paper.ink.toArgb()); footer.setTextColor(paper.ink.toArgb())
            androidx.core.view.WindowCompat.getInsetsController(window, root).apply {
                isAppearanceLightStatusBars = !paper.dark; isAppearanceLightNavigationBars = !paper.dark
            }
        }
        header.setContent {
            // The chrome follows the paper, so a light page never carries the app's dark controls.
            ReaderTheme(appearance.paper) {
                syncDialog?.let { ReaderSyncDialog(it) { syncDialog = null } }
                dictionaryWord?.let { DictionarySheet(it) { dictionaryWord = null } }
                moreSelection?.let { entry -> SelectionActionsSheet(entry.quote, { moreSelection = null }) { action ->
                    moreSelection = null; selectionAction(entry, action)
                } }
                selectionBounds?.let { bounds -> selection?.let { selected ->
                    ReaderSelectionToolbar(bounds) { action ->
                        val entry = ReadingNote(locator = selected.locator)
                        selection = null; selectionBounds = null
                        // Let WebView finish its own action mode when the DOM selection clears.
                        activeNavigator?.clearSelection()
                        if (action == null) moreSelection = entry else selectionAction(entry, action)
                    }
                } }
                if (showWords) VocabularySheet(words, { showWords = false }, { defineWord(it) }, { entry -> saveWords(words.filterNot { it.id == entry.id }) })
                if (transferNotes) NotesTransferSheet(id, { transferNotes = false }, {
                    notes = notesStore.all(); bookmarks = bookmarksStore.all(); words = wordsStore.all()
                    lifecycleScope.launch { decorate() }
                })
                exploreTab?.let { tab -> publication?.let { book -> activeNavigator?.let { nav ->
                    ReaderNavigation(book, tab, bookmarks, { nav.currentLocator.value }, { updated ->
                        lifecycleScope.launch {
                            try { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { bookmarksStore.save(updated) }; bookmarks = updated }
                            catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                            catch (error: Exception) { showSyncDialog(ReaderDialog("Could not save bookmark", error.message)) }
                        }
                    }, { nav.go(it) }, { nav.go(it) }, { exploreTab = null })
                } } }
                if (showNotes) ReadingNotesSheet(notes, { showNotes = false }, { entry ->
                    if (activeNavigator?.go(entry.locator) == true) showNotes = false
                }, { editingNote = it }, { entry -> saveNotes { current -> current.filterNot { it.id == entry.id } } }, { showNotes = false; transferNotes = true })
                editingNote?.let { entry -> ReadingNoteEditor(entry, { editingNote = null }) { updated ->
                    saveNotes { current -> current.filterNot { it.id == updated.id } + updated }
                } }
                val colors = androidx.compose.material3.MaterialTheme.colorScheme
                androidx.compose.runtime.SideEffect {
                    controlShellColor = colors.surfaceContainer.toArgb()
                    updateControlShell()
                    completion.setTextColor(colors.onPrimary.toArgb())
                    completion.backgroundTintList = android.content.res.ColorStateList.valueOf(colors.primary.toArgb())
                    syncButton.setTextColor(colors.primary.toArgb())
                    syncButton.backgroundTintList = android.content.res.ColorStateList.valueOf(colors.surfaceContainer.toArgb())
                    listOf(back, previous, next).forEachIndexed { index, button ->
                        button.setTextColor(colors.onSecondaryContainer.toArgb())
                        button.background = android.graphics.drawable.RippleDrawable(
                            android.content.res.ColorStateList.valueOf(colors.primary.copy(alpha = .2f).toArgb()),
                            controlShape(index, colors.secondaryContainer.toArgb()),
                            controlShape(index, android.graphics.Color.WHITE)
                        )
                        button.invalidate()
                    }
                }
                ReaderMenu({ syncButton.isEnabled }, { syncButton.performClick() }, pinned,
                    appearance, { updated ->
                        appearance = updated; updated.save(prefs)
                        applyPaper(updated.paper)
                        activeNavigator?.submitPreferences(updated.preferences())
                    }, { showNotes = true }, { exploreTab = it }, { showWords = true }) { checked ->
                    pinned = checked; prefs.edit().putBoolean("reader:buttons", checked).apply()
                    navigationSpace.visibility = if (checked) View.VISIBLE else View.GONE
                    controls.visibility = if (checked) View.VISIBLE else View.GONE
                }
            }
        }
        setContentView(overlay)
        applyPaper(appearance.paper)
        androidx.core.view.WindowCompat.getInsetsController(window, root).apply {
            hide(WindowInsetsCompat.Type.statusBars())
            systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        lifecycleScope.launch {
            try {
                val opened = Books(this@ReaderActivity).open(LibraryStore(this@ReaderActivity).file(file))
                publication = opened
                val positions = opened.positions()
                val totalPages = positions.size
                val saved = prefs.getString("ebook:$id", null)?.let { runCatching { Locator.fromJSON(JSONObject(it)) }.getOrNull() }
                supportFragmentManager.fragmentFactory = EpubNavigatorFactory(opened).createFragmentFactory(
                    initialLocator = saved,
                    initialPreferences = appearance.preferences(),
                    configuration = EpubNavigatorFragment.Configuration(shouldApplyInsetsPadding = false,
                        selectionActionModeCallback = object : android.view.ActionMode.Callback {
                            private var watch: kotlinx.coroutines.Job? = null
                            override fun onCreateActionMode(mode: android.view.ActionMode, menu: android.view.Menu): Boolean {
                                menu.clear()
                                selectionMode = mode
                                watch?.cancel()
                                watch = lifecycleScope.launch {
                                    while (selectionMode === mode) {
                                        val current = activeNavigator?.currentSelection()
                                        if (!current?.locator?.text?.highlight.isNullOrBlank()) {
                                            selection = current
                                            val location = IntArray(2)
                                            activeNavigator?.view?.getLocationInWindow(location)
                                            val center = (activeNavigator?.view?.width ?: 0) / 2f
                                            val anchor = current?.rect ?: android.graphics.RectF(center, 0f, center, dp(48).toFloat())
                                            selectionBounds = android.graphics.RectF(anchor).apply {
                                                offset(location[0].toFloat(), location[1].toFloat())
                                            }
                                        } else { selection = null; selectionBounds = null }
                                        delay(150)
                                    }
                                }
                                return true
                            }
                            override fun onPrepareActionMode(mode: android.view.ActionMode, menu: android.view.Menu): Boolean {
                                menu.clear()
                                return true
                            }
                            override fun onDestroyActionMode(mode: android.view.ActionMode) {
                                if (selectionMode === mode) { watch?.cancel(); selectionMode = null; selection = null; selectionBounds = null }
                            }
                            override fun onActionItemClicked(mode: android.view.ActionMode, item: android.view.MenuItem) = false
                        }).apply { useReadiumCssFontSize = true }
                )
                supportFragmentManager.beginTransaction().add(container.id, EpubNavigatorFragment::class.java, null, "reader").commitNow()
                val navigator = supportFragmentManager.findFragmentByTag("reader") as EpubNavigatorFragment
                activeNavigator = navigator
                decorate()
                navigator.addDecorationListener("notes", object : org.readium.r2.navigator.DecorableNavigator.Listener {
                    override fun onDecorationActivated(event: org.readium.r2.navigator.DecorableNavigator.OnActivatedEvent): Boolean {
                        editingNote = notes.find { it.id == event.decoration.id }
                        return editingNote != null
                    }
                })
                suspend fun forward(animated: Boolean): Boolean {
                    // WebView's native scroll range can retain a fractional remainder at
                    // the last column. Use the rendered document boundary to cross chapters.
                    val atEnd = navigator.evaluateJavascript("(function(){var e=document.scrollingElement;if(!e||!e.clientWidth)return false;var page=Math.round(Math.abs(e.scrollLeft)/e.clientWidth);var count=Math.max(1,Math.round(e.scrollWidth/e.clientWidth));return page>=count-1;})()") == "true"
                    if (atEnd) {
                        val href = navigator.currentLocator.value.href.toString().substringBefore('#')
                        val index = opened.readingOrder.indexOfFirst { it.href.toString().substringBefore('#') == href }
                        if (index >= 0) return opened.readingOrder.getOrNull(index + 1)?.let { navigator.go(it, animated) } ?: false
                    }
                    return navigator.goForward(animated)
                }
                navigator.addInputListener(object : org.readium.r2.navigator.input.InputListener {
                    override fun onTap(event: org.readium.r2.navigator.input.TapEvent): Boolean {
                        if (fold.folding) return true
                        val fraction = event.point.x / fold.width.coerceAtLeast(1)
                        when {
                            fraction < .25f -> navigator.goBackward(animated = true)
                            fraction > .75f -> lifecycleScope.launch { forward(animated = true) }
                            else -> toggleControls()
                        }
                        return true
                    }
                })
                var showPages by mutableStateOf(false)
                var currentPage by mutableStateOf(1)
                val previews = PagePreviews(opened, positions)
                overlay.addView(ComposeView(this@ReaderActivity).apply {
                    setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
                    setContent {
                        // Loaded with the reader, so the picker's front cover is ready when it opens.
                        val artwork = rememberArtwork(title, androidx.compose.runtime.remember { BookServer(this@ReaderActivity) })
                        val cover = androidx.compose.runtime.remember(artwork) { artwork?.asImageBitmap() }
                        if (showPages && totalPages > 0) ReaderTheme(appearance.paper) {
                            androidx.compose.runtime.DisposableEffect(Unit) { onDispose { previews.release() } }
                            PagePicker(title.name, title.author, cover, appearance.paper, totalPages, currentPage, previews.chapters, previews::excerpt,
                                onDismiss = { showPages = false }, onSelect = { page ->
                                    if (navigator.go(positions[page - 1])) showPages = false
                                })
                        }
                    }
                }, android.widget.FrameLayout.LayoutParams(-1, -1))
                pageCounter.isFocusable = totalPages > 0
                if (totalPages > 0) pageCounter.setOnClickListener { showPages = true }
                val sync = ReaderSync(this@ReaderActivity, title, syncButton, navigator, positions) {
                    completion.text = if (progress.finished) "Finished · 100% · Undo" else "Mark as finished"
                }
                syncButton.isEnabled = true
                syncButton.setOnClickListener { sync.show() }
                previous.setOnClickListener { if (!fold.folding) navigator.goBackward(animated = true) }
                next.setOnClickListener { if (!fold.folding) lifecycleScope.launch { forward(animated = true) } }
                fun recordLocator(locator: Locator) {
                    progress.record(locator.toJSON().toString(), locator.locations.totalProgression ?: 0.0)
                    val percent = progress.percent
                    footer.text = "$percent%"
                    if (totalPages > 0) {
                        val page = (locator.locations.position ?: ((locator.locations.totalProgression ?: 0.0) * (totalPages - 1)).toInt() + 1).coerceIn(1, totalPages)
                        currentPage = page
                        completion.visibility = if (page == totalPages) View.VISIBLE else View.GONE
                        pageCounter.text = "~$page / $totalPages"
                        pageCounter.contentDescription = "Estimated ebook page $page of $totalPages. Open page picker"
                    } else {
                        completion.visibility = if ((locator.locations.totalProgression ?: 0.0) >= 1.0) View.VISIBLE else View.GONE
                        pageCounter.text = "$percent%"
                        pageCounter.contentDescription = "$percent percent read; page count unavailable"
                    }
                }
                fold.beginTurn = { complete ->
                    lifecycleScope.launch {
                        // Readium's Boolean only confirms dispatch, not that a page moved.
                        // Keep the old page covering the reader until its viewport advances.
                        suspend fun viewport() = navigator.evaluateJavascript(
                            "JSON.stringify([location.href, window.scrollX, window.scrollY])"
                        )
                        var advanced = false
                        try {
                            val before = viewport()
                            val beforeLocator = navigator.currentLocator.value
                            if (forward(animated = false)) {
                                advanced = withTimeoutOrNull(5000) {
                                    var stableViewport: String? = null
                                    var stableFrames = 0
                                    while (true) {
                                        delay(32)
                                        val now = viewport()
                                        val moved = if (before != null) now != null && now != before
                                            else navigator.currentLocator.value != beforeLocator
                                        val ready = navigator.evaluateJavascript("document.readyState === 'complete' && (!document.fonts || document.fonts.status === 'loaded') && Array.from(document.images).every(function(i){return i.complete;})") == "true"
                                        stableFrames = if (moved && ready && now == stableViewport) stableFrames + 1 else 0
                                        stableViewport = now
                                        if (stableFrames >= 2) break
                                    }
                                    // A changed URL/locator is not a rendered chapter. Wait for
                                    // WebView's compositor before revealing the new document.
                                    suspend fun awaitVisual(view: View) {
                                        if (view is android.webkit.WebView && view.isShown) {
                                            kotlinx.coroutines.suspendCancellableCoroutine<Unit> { continuation ->
                                                view.postVisualStateCallback(0, object : android.webkit.WebView.VisualStateCallback() {
                                                    override fun onComplete(requestId: Long) {
                                                        if (continuation.isActive) continuation.resumeWith(Result.success(Unit))
                                                    }
                                                })
                                            }
                                        } else if (view is android.view.ViewGroup) {
                                            for (i in 0 until view.childCount) awaitVisual(view.getChildAt(i))
                                        }
                                    }
                                    awaitVisual(container)
                                    true
                                } ?: false
                            }
                        } finally {
                            complete(advanced)
                        }
                    }
                }
                fold.turnReleased = { recordLocator(navigator.currentLocator.value) }
                navigator.currentLocator.collect { locator -> if (!fold.folding) recordLocator(locator) }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Exception) { android.widget.Toast.makeText(this@ReaderActivity, error.message ?: "Unable to open this book.", android.widget.Toast.LENGTH_LONG).show() }
        }
    }
    override fun onDestroy() { super.onDestroy(); publication?.close() }
}
