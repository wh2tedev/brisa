@file:OptIn(ExperimentalMaterial3Api::class)

package app.brisa.browser

import android.text.format.DateUtils
import android.view.HapticFeedbackConstants
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import kotlinx.coroutines.launch

@Composable
fun BrowserScreen(state: BrowserState, host: BrowserHost) {
    val scheme = MaterialTheme.colorScheme
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current

    LaunchedEffect(state.selectNonce) { focusManager.clearFocus() }
    LaunchedEffect(state.findVisible) { if (!state.findVisible) keyboard?.hide() }

    // Registered in this order so that the most specific state wins; the activity handles page history.
    BackHandler(enabled = state.findVisible) { host.closeFind() }
    BackHandler(enabled = state.urlFocused) { focusManager.clearFocus() }
    BackHandler(enabled = state.customView != null) { host.exitFullscreen() }

    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .background(scheme.surfaceContainer)
                .windowInsetsPadding(WindowInsets.systemBars.union(WindowInsets.displayCutout))
                .imePadding(),
        ) {
            if (!state.toolbarBottom) {
                BarSlot(state, host)
                LoadProgress(state)
            }
            WebArea(state, host, Modifier.weight(1f))
            AnimatedVisibility(
                visible = state.findVisible,
                enter = fadeIn(tween(Motion.MEDIUM, easing = Motion.decelerate)) +
                    slideInVertically(tween(Motion.MEDIUM, easing = Motion.decelerate)) { it },
                exit = fadeOut(tween(Motion.SHORT, easing = Motion.accelerate)) +
                    slideOutVertically(tween(Motion.SHORT, easing = Motion.accelerate)) { it },
            ) { FindBar(state, host) }
            if (state.toolbarBottom) {
                LoadProgress(state)
                BarSlot(state, host)
            }
        }

        val custom = state.customView
        if (custom != null) {
            AndroidView(
                factory = { ctx ->
                    FrameLayout(ctx).apply {
                        setBackgroundColor(android.graphics.Color.BLACK)
                        addView(custom, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
        }

        when (state.sheet) {
            Sheet.TABS -> TabsSheet(state, host)
            Sheet.BOOKMARKS -> BookmarksSheet(state, host)
            Sheet.HISTORY -> HistorySheet(state, host)
            null -> Unit
        }
        state.prompt?.let { PromptDialog(it, state) }
    }
}

// region bars

/** The address bar, which slides away while the page is scrolled down. */
@Composable
private fun BarSlot(state: BrowserState, host: BrowserHost) {
    AnimatedVisibility(
        visible = state.barVisible || state.urlFocused,
        enter = expandVertically(tween(Motion.MEDIUM, easing = Motion.decelerate)) + fadeIn(tween(Motion.MEDIUM)),
        exit = shrinkVertically(tween(Motion.MEDIUM, easing = Motion.accelerate)) + fadeOut(tween(Motion.SHORT)),
    ) { TopBar(state, host) }
}

@Composable
private fun TopBar(state: BrowserState, host: BrowserHost) {
    val left = state.oneHand == "left"
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = if (left) 4.dp else 12.dp, end = if (left) 12.dp else 4.dp, top = 9.dp, bottom = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (left) {
            MenuButton(state, host)
            TabsButton(state)
        }
        Omnibox(state, host, Modifier.weight(1f).height(46.dp))
        if (!left) {
            TabsButton(state)
            MenuButton(state, host)
        }
    }
}

@Composable
private fun MenuButton(state: BrowserState, host: BrowserHost) {
    var menuOpen by remember { mutableStateOf(false) }
    var info by remember { mutableStateOf<MenuInfo?>(null) }
    val view = LocalView.current
    Box {
        PressableIconButton(onClick = {
            view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
            info = host.menuInfo()
            menuOpen = true
        }) {
            Icon(painterResource(R.drawable.ic_more_vert), contentDescription = stringResource(R.string.more))
        }
        BrowserMenu(menuOpen, info, state, host) { menuOpen = false }
    }
}

@Composable
private fun Omnibox(state: BrowserState, host: BrowserHost, modifier: Modifier) {
    val scheme = MaterialTheme.colorScheme
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val focused = state.urlFocused
    val shown = if (focused) state.typed else TextFieldValue(if (state.locked) "" else displayUrl(state.pageUrl))
    val border by animateColorAsState(
        targetValue = if (focused) scheme.primary else Color.Transparent,
        animationSpec = tween(Motion.MEDIUM, easing = Motion.standard),
        label = "omniboxBorder",
    )
    val shape = RoundedCornerShape(24.dp)
    Row(
        modifier
            .clip(shape)
            .background(if (state.currentPrivate) scheme.primaryContainer else scheme.surfaceContainerHighest)
            .border(2.dp, border, shape),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val url = state.pageUrl
        val (icon, tint) = when {
            state.currentPrivate && !focused -> R.drawable.ic_visibility_off to scheme.onSurfaceVariant
            focused || url.isNullOrEmpty() -> R.drawable.ic_search to scheme.onSurfaceVariant
            url.startsWith("https://") -> R.drawable.ic_lock to scheme.onSurfaceVariant
            else -> R.drawable.ic_lock_open to scheme.error
        }
        Crossfade(targetState = icon, modifier = Modifier.padding(start = 14.dp), label = "siteIcon") { res ->
            Icon(painterResource(res), contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
        }
        BasicTextField(
            value = shown,
            onValueChange = { state.typed = it },
            singleLine = true,
            textStyle = TextStyle(color = scheme.onSurface, fontSize = 15.sp),
            cursorBrush = SolidColor(scheme.primary),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = {
                host.submit(state.typed.text)
                focusManager.clearFocus()
                keyboard?.hide()
            }),
            modifier = Modifier
                .weight(1f)
                .padding(start = 10.dp, end = 6.dp)
                .onFocusChanged { f ->
                    if (f.isFocused != state.urlFocused) {
                        state.urlFocused = f.isFocused
                        if (f.isFocused) {
                            val full = host.currentUrl()
                            state.typed = TextFieldValue(full, TextRange(0, full.length))
                        }
                    }
                },
            decorationBox = { inner ->
                Box(Modifier.fillMaxHeight(), contentAlignment = Alignment.CenterStart) {
                    if (shown.text.isEmpty()) {
                        Text(
                            stringResource(R.string.url_hint),
                            color = scheme.onSurfaceVariant,
                            fontSize = 15.sp,
                            maxLines = 1,
                        )
                    }
                    inner()
                }
            },
        )
        AnimatedVisibility(
            visible = focused && state.typed.text.isNotEmpty(),
            enter = fadeIn(tween(Motion.SHORT, easing = Motion.decelerate)) + scaleIn(tween(Motion.SHORT), initialScale = 0.6f),
            exit = fadeOut(tween(100)) + scaleOut(tween(100), targetScale = 0.6f),
        ) {
            PressableIconButton(onClick = { state.typed = TextFieldValue("") }, modifier = Modifier.size(40.dp)) {
                Icon(painterResource(R.drawable.ic_close), contentDescription = stringResource(R.string.clear), modifier = Modifier.size(20.dp))
            }
        }
    }
}

@Composable
private fun TabsButton(state: BrowserState) {
    val scheme = MaterialTheme.colorScheme
    val source = remember { MutableInteractionSource() }
    val count = state.tabEntries.size.coerceAtLeast(1)
    val bounce = remember { Animatable(1f) }
    LaunchedEffect(count) {
        bounce.snapTo(1.4f)
        bounce.animateTo(1f, spring(dampingRatio = 0.5f, stiffness = Spring.StiffnessMediumLow))
    }
    val label = stringResource(R.string.tabs)
    Box(
        Modifier
            .size(44.dp)
            .pressScale(source)
            .clip(CircleShape)
            .semantics(mergeDescendants = true) { contentDescription = label }
            .clickable(interactionSource = source, indication = LocalIndication.current) { state.sheet = Sheet.TABS },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(26.dp)
                .graphicsLayer {
                    scaleX = bounce.value
                    scaleY = bounce.value
                }
                .border(2.dp, scheme.onSurface, RoundedCornerShape(8.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Text(count.toString(), color = scheme.onSurface, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun LoadProgress(state: BrowserState) {
    val scheme = MaterialTheme.colorScheme
    AnimatedVisibility(
        visible = state.loading,
        enter = EnterTransition.None,
        exit = fadeOut(tween(260, delayMillis = 120)),
    ) {
        val shown by animateFloatAsState(state.progress / 100f, label = "pageProgress")
        LinearProgressIndicator(
            progress = { shown },
            modifier = Modifier.fillMaxWidth().height(3.dp),
            color = scheme.primary,
            trackColor = scheme.surfaceContainer,
            gapSize = 0.dp,
            drawStopIndicator = {},
        )
    }
}

@Composable
private fun FindBar(state: BrowserState, host: BrowserHost) {
    val scheme = MaterialTheme.colorScheme
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        focus.requestFocus()
        keyboard?.show()
    }
    Row(
        Modifier
            .fillMaxWidth()
            .background(scheme.surfaceContainer)
            .padding(start = 20.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicTextField(
            value = state.findQuery,
            onValueChange = {
                state.findQuery = it
                host.onFind(it)
            },
            singleLine = true,
            textStyle = TextStyle(color = scheme.onSurface, fontSize = 15.sp),
            cursorBrush = SolidColor(scheme.primary),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { host.findNext(true) }),
            modifier = Modifier.weight(1f).height(48.dp).focusRequester(focus),
            decorationBox = { inner ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (state.findQuery.isEmpty()) {
                        Text(stringResource(R.string.find_hint), color = scheme.onSurfaceVariant, fontSize = 15.sp)
                    }
                    inner()
                }
            },
        )
        Text(
            state.findCount,
            color = scheme.onSurfaceVariant,
            fontSize = 13.sp,
            modifier = Modifier.padding(horizontal = 8.dp),
        )
        PressableIconButton(onClick = { host.findNext(false) }) {
            Icon(painterResource(R.drawable.ic_arrow_up), contentDescription = stringResource(R.string.find_previous))
        }
        PressableIconButton(onClick = { host.findNext(true) }) {
            Icon(painterResource(R.drawable.ic_arrow_down), contentDescription = stringResource(R.string.find_next))
        }
        PressableIconButton(onClick = { host.closeFind() }) {
            Icon(painterResource(R.drawable.ic_close), contentDescription = stringResource(R.string.close))
        }
    }
}

// endregion

// region menu

@Composable
private fun BrowserMenu(
    expanded: Boolean,
    info: MenuInfo?,
    state: BrowserState,
    host: BrowserHost,
    onDismiss: () -> Unit,
) {
    if (info == null) return
    val scheme = MaterialTheme.colorScheme
    fun act(action: () -> Unit) {
        onDismiss()
        action()
    }

    @Composable
    fun item(@DrawableRes icon: Int, @androidx.annotation.StringRes text: Int, action: () -> Unit) {
        DropdownMenuItem(
            text = { Text(stringResource(text)) },
            leadingIcon = { Icon(painterResource(icon), contentDescription = null, tint = scheme.onSurfaceVariant) },
            onClick = { act(action) },
        )
    }

    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss, modifier = Modifier.width(272.dp)) {
        Row(Modifier.fillMaxWidth().height(52.dp)) {
            PressableIconButton(
                onClick = { act { host.goBackPage() } },
                enabled = info.canGoBack,
                modifier = Modifier.weight(1f).fillMaxHeight(),
            ) { Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = stringResource(R.string.back)) }
            PressableIconButton(
                onClick = { act { host.goForwardPage() } },
                enabled = info.canGoForward,
                modifier = Modifier.weight(1f).fillMaxHeight(),
            ) { Icon(painterResource(R.drawable.ic_arrow_forward), contentDescription = stringResource(R.string.forward)) }
            PressableIconButton(
                onClick = { act { host.toggleBookmark() } },
                enabled = info.canBookmark,
                modifier = Modifier.weight(1f).fillMaxHeight(),
            ) {
                Icon(
                    painterResource(if (info.bookmarked) R.drawable.ic_star else R.drawable.ic_star_border),
                    contentDescription = stringResource(if (info.bookmarked) R.string.remove_bookmark else R.string.add_bookmark),
                )
            }
            PressableIconButton(
                onClick = { act { host.reloadOrStop() } },
                modifier = Modifier.weight(1f).fillMaxHeight(),
            ) {
                Icon(
                    painterResource(if (info.loading) R.drawable.ic_close else R.drawable.ic_refresh),
                    contentDescription = stringResource(if (info.loading) R.string.stop else R.string.reload),
                )
            }
        }
        HorizontalDivider(Modifier.padding(vertical = 4.dp), color = scheme.outlineVariant)
        item(R.drawable.ic_add, R.string.new_tab) { host.newTab() }
        item(R.drawable.ic_visibility_off, R.string.new_private_tab) { host.newPrivateTab() }
        item(R.drawable.ic_search, R.string.find_in_page) { host.openFind() }
        item(R.drawable.ic_share, R.string.share) { host.share() }
        item(R.drawable.ic_star, R.string.bookmarks) { state.sheet = Sheet.BOOKMARKS }
        item(R.drawable.ic_download, R.string.downloads) { host.openDownloads() }
        item(R.drawable.ic_history, R.string.history) { state.sheet = Sheet.HISTORY }
        DropdownMenuItem(
            text = { Text(stringResource(R.string.desktop_site)) },
            leadingIcon = { Icon(painterResource(R.drawable.ic_desktop), contentDescription = null, tint = scheme.onSurfaceVariant) },
            trailingIcon = { Switch(checked = info.desktop, onCheckedChange = null) },
            onClick = { act { host.toggleDesktop() } },
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.lock_tab)) },
            leadingIcon = { Icon(painterResource(R.drawable.ic_lock), contentDescription = null, tint = scheme.onSurfaceVariant) },
            trailingIcon = { Switch(checked = info.tabLocked, onCheckedChange = null) },
            onClick = { act { host.toggleTabLock() } },
        )
        if (info.siteLockable) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.lock_site)) },
                leadingIcon = { Icon(painterResource(R.drawable.ic_lock), contentDescription = null, tint = scheme.onSurfaceVariant) },
                trailingIcon = { Switch(checked = info.siteLocked, onCheckedChange = null) },
                onClick = { act { host.toggleSiteLock() } },
            )
        }
        if (info.showProtection) {
            DropdownMenuItem(
                text = {
                    Column {
                        Text(stringResource(R.string.protection_row))
                        Text(
                            if (info.protectionOn) stringResource(R.string.blocked_on_page, info.blocked)
                            else stringResource(R.string.protection_off_site),
                            style = MaterialTheme.typography.bodySmall,
                            color = scheme.onSurfaceVariant,
                        )
                    }
                },
                leadingIcon = { Icon(painterResource(R.drawable.ic_shield), contentDescription = null, tint = scheme.onSurfaceVariant) },
                trailingIcon = { Switch(checked = info.protectionOn, onCheckedChange = null) },
                onClick = { act { host.setProtection(!info.protectionOn) } },
            )
        }
        item(R.drawable.ic_settings, R.string.settings) { host.openSettings() }
    }
}

// endregion

// region web area

@Composable
private fun LockedCover(host: BrowserHost) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        modifier = Modifier
            .fillMaxSize()
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
        color = scheme.surface,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
        ) {
            Icon(painterResource(R.drawable.ic_lock), contentDescription = null, tint = scheme.onSurfaceVariant, modifier = Modifier.size(40.dp))
            Text(
                stringResource(R.string.page_locked),
                color = scheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 12.dp, bottom = 20.dp),
            )
            Button(onClick = { host.unlockCurrent() }) { Text(stringResource(R.string.lock_unlock)) }
        }
    }
}

private class Suggestion(@DrawableRes val icon: Int, val title: String, val subtitle: String, val url: String)

@Composable
private fun WebArea(state: BrowserState, host: BrowserHost, modifier: Modifier) {
    val scheme = MaterialTheme.colorScheme
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val reveal = remember { Animatable(1f) }
    LaunchedEffect(state.selectNonce) {
        reveal.snapTo(0f)
        reveal.animateTo(1f, tween(Motion.SHORT, easing = Motion.decelerate))
    }
    val accent = scheme.primary.toArgb()
    val spinnerBg = scheme.surfaceContainerHigh.toArgb()

    Box(modifier.background(scheme.surface)) {
        AndroidView(
            factory = { ctx ->
                SwipeRefreshLayout(ctx).apply {
                    addView(
                        host.webContainer,
                        ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
                    )
                    setOnChildScrollUpCallback { _, _ -> host.canScrollUp() }
                    setOnRefreshListener {
                        state.refreshing = true
                        host.reload()
                    }
                }
            },
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    alpha = 0.4f + 0.6f * reveal.value
                },
            update = { layout ->
                layout.isEnabled = state.pullEnabled
                layout.isRefreshing = state.refreshing
                layout.setColorSchemeColors(accent)
                layout.setProgressBackgroundColorSchemeColor(spinnerBg)
            },
        )

        if (state.locked) LockedCover(host)

        val typed = state.typed.text
        val show = state.urlFocused && typed.isNotEmpty() && typed != host.currentUrl()
        var rows by remember { mutableStateOf(emptyList<Suggestion>()) }
        LaunchedEffect(typed, show) {
            if (show) rows = buildSuggestions(context, host, typed.trim())
        }
        val bottom = state.toolbarBottom
        AnimatedVisibility(
            visible = show && rows.isNotEmpty(),
            modifier = Modifier.align(if (bottom) Alignment.BottomCenter else Alignment.TopCenter),
            enter = fadeIn(tween(Motion.SHORT, easing = Motion.decelerate)) +
                slideInVertically(tween(Motion.SHORT, easing = Motion.decelerate)) { if (bottom) it / 8 else -it / 8 },
            exit = fadeOut(tween(100)),
        ) {
            Surface(color = scheme.surfaceContainerHigh, shadowElevation = 8.dp) {
                Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    rows.forEach { row ->
                        SuggestionRow(row) {
                            host.openUrl(row.url)
                            focusManager.clearFocus()
                            keyboard?.hide()
                        }
                    }
                }
            }
        }
        SnackbarHost(state.snackbar, Modifier.align(Alignment.BottomCenter))
    }
}

private fun buildSuggestions(context: android.content.Context, host: BrowserHost, query: String): List<Suggestion> {
    if (query.isEmpty()) return emptyList()
    val target = host.resolveInput(query)
    val isSearch = target == host.searchUrl(query)
    val first = Suggestion(
        if (isSearch) R.drawable.ic_search else R.drawable.ic_arrow_forward,
        query,
        if (isSearch) context.getString(R.string.search_with, host.engineName()) else target,
        target,
    )
    fun matches(title: String, url: String) =
        title.contains(query, ignoreCase = true) || url.contains(query, ignoreCase = true)

    val bookmarks = Bookmarks.all(context).filter { matches(it.title, it.url) }
        .map { Suggestion(R.drawable.ic_star, it.title.ifBlank { it.url }, displayUrl(it.url), it.url) }
    val visited = HistoryView.all(context).filter { matches(it.title, it.url) }
        .map { Suggestion(R.drawable.ic_history, it.title.ifBlank { it.url }, displayUrl(it.url), it.url) }
    val seen = mutableSetOf<String>()
    return listOf(first) + (bookmarks + visited).filter { seen.add(it.url) }.take(5)
}

@Composable
private fun SuggestionRow(row: Suggestion, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .height(56.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(painterResource(row.icon), contentDescription = null, tint = scheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
        Column(Modifier.weight(1f).padding(start = 18.dp)) {
            Text(row.title, color = scheme.onSurface, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (row.subtitle.isNotEmpty()) {
                Text(row.subtitle, color = scheme.onSurfaceVariant, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

// endregion

// region sheets

private val avatarColors = listOf(0xFF4285F4, 0xFF34A853, 0xFFA142F4, 0xFFF29900, 0xFFE8438A, 0xFF12B5CB)

@Composable
private fun ListSheet(
    title: String,
    onDismiss: () -> Unit,
    buttonLabel: String?,
    onButton: (dismiss: () -> Unit) -> Unit,
    headerAction: (@Composable () -> Unit)? = null,
    content: LazyListScope.(dismiss: () -> Unit) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val dismiss: () -> Unit = {
        scope.launch { sheetState.hide() }.invokeOnCompletion { onDismiss() }
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = scheme.surfaceContainerHigh,
    ) {
        Row(
            Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                title,
                color = scheme.onSurfaceVariant,
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f).padding(vertical = 8.dp),
            )
            headerAction?.invoke()
        }
        val maxList = (LocalConfiguration.current.screenHeightDp * 0.45f).dp
        LazyColumn(Modifier.heightIn(max = maxList)) { content(this, dismiss) }
        if (buttonLabel != null) {
            FilledTonalButton(
                onClick = { onButton(dismiss) },
                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
            ) { Text(buttonLabel) }
        }
    }
}

@Composable
private fun EntryRow(
    title: String,
    subtitle: String,
    favicon: android.graphics.Bitmap?,
    selected: Boolean,
    onOpen: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    isPrivate: Boolean = false,
) {
    val scheme = MaterialTheme.colorScheme
    val seed = subtitle.ifEmpty { title }
    val color = Color(avatarColors[(seed.hashCode() and 0x7fffffff) % avatarColors.size])
    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 1.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(if (selected) scheme.surfaceContainerHighest else Color.Transparent)
            .clickable(onClick = onOpen)
            .height(68.dp)
            .padding(start = 12.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(42.dp).clip(CircleShape).background(color.copy(alpha = 0.2f)),
            contentAlignment = Alignment.Center,
        ) {
            if (isPrivate) {
                Icon(painterResource(R.drawable.ic_visibility_off), contentDescription = null, tint = color, modifier = Modifier.size(22.dp))
            } else if (favicon != null) {
                Image(favicon.asImageBitmap(), contentDescription = null, modifier = Modifier.size(24.dp))
            } else {
                Text(
                    seed.firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()?.toString().orEmpty(),
                    color = color,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
        Column(Modifier.weight(1f).padding(start = 14.dp)) {
            Text(title, color = scheme.onSurface, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle.isNotEmpty() && subtitle != title) {
                Text(
                    subtitle,
                    color = scheme.onSurfaceVariant,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        PressableIconButton(onClick = onClose) {
            Icon(painterResource(R.drawable.ic_close), contentDescription = stringResource(R.string.close_tab))
        }
    }
}

@Composable
private fun EmptyText(@androidx.annotation.StringRes text: Int) {
    Text(
        stringResource(text),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
    )
}

@Composable
private fun TabsSheet(state: BrowserState, host: BrowserHost) {
    ListSheet(
        title = stringResource(R.string.tabs),
        onDismiss = { state.sheet = null },
        buttonLabel = stringResource(R.string.new_tab),
        onButton = { dismiss ->
            host.newTab()
            dismiss()
        },
    ) { dismiss ->
        items(state.tabEntries, key = { it.id }) { entry ->
            EntryRow(
                title = entry.title,
                subtitle = entry.subtitle,
                favicon = entry.favicon,
                selected = entry.selected,
                onOpen = {
                    host.selectTab(entry.id)
                    dismiss()
                },
                onClose = { host.closeTab(entry.id) },
                modifier = Modifier.animateItem(),
                isPrivate = entry.isPrivate,
            )
        }
    }
}

@Composable
private fun BookmarksSheet(state: BrowserState, host: BrowserHost) {
    val context = LocalContext.current
    val list = remember { mutableStateListOf<Bookmarks.Item>().apply { addAll(Bookmarks.all(context)) } }
    ListSheet(
        title = stringResource(R.string.bookmarks),
        onDismiss = { state.sheet = null },
        buttonLabel = null,
        onButton = {},
    ) { dismiss ->
        if (list.isEmpty()) item { EmptyText(R.string.empty_bookmarks) }
        items(list, key = { it.url }) { entry ->
            EntryRow(
                title = entry.title.ifBlank { entry.url },
                subtitle = displayUrl(entry.url),
                favicon = null,
                selected = false,
                onOpen = {
                    host.openUrl(entry.url)
                    dismiss()
                },
                onClose = {
                    Bookmarks.remove(context, entry.url)
                    list.remove(entry)
                },
                modifier = Modifier.animateItem(),
            )
        }
    }
}

@Composable
private fun HistorySheet(state: BrowserState, host: BrowserHost) {
    val context = LocalContext.current
    var decoy by remember { mutableStateOf(DecoyHistory.enabled(context)) }
    val list = remember { mutableStateListOf<History.Entry>().apply { addAll(HistoryView.all(context).take(100)) } }
    fun reload() {
        list.clear()
        list.addAll(HistoryView.all(context).take(100))
    }
    ListSheet(
        title = stringResource(R.string.history),
        onDismiss = { state.sheet = null },
        buttonLabel = stringResource(R.string.clear_history),
        onButton = {
            HistoryView.clear(context)
            list.clear()
        },
        headerAction = {
            PressableIconButton(onClick = {
                if (!decoy) {
                    DecoyHistory.setEnabled(context, true)
                    decoy = true
                    reload()
                } else {
                    // Showing the real list again needs the phone's own lock, so nobody else can switch it back.
                    host.confirmIdentity {
                        DecoyHistory.setEnabled(context, false)
                        decoy = false
                        reload()
                    }
                }
            }) {
                Icon(
                    painterResource(if (decoy) R.drawable.ic_visibility else R.drawable.ic_visibility_off),
                    contentDescription = stringResource(if (decoy) R.string.show_real_history else R.string.hide_real_history),
                )
            }
        },
    ) { dismiss ->
        if (list.isEmpty()) item { EmptyText(R.string.empty_history) }
        items(list, key = { it.url }) { entry ->
            val age = DateUtils.getRelativeTimeSpanString(entry.time, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)
            EntryRow(
                title = entry.title.ifBlank { entry.url },
                subtitle = "${displayUrl(entry.url)} · $age",
                favicon = null,
                selected = false,
                onOpen = {
                    host.openUrl(entry.url)
                    dismiss()
                },
                onClose = {
                    HistoryView.remove(context, entry.url)
                    list.remove(entry)
                },
                modifier = Modifier.animateItem(),
            )
        }
    }
}

// endregion

// region dialogs

@Composable
private fun PromptDialog(prompt: Prompt, state: BrowserState) {
    fun close() {
        state.prompt = null
    }
    when (prompt) {
        is Prompt.Download -> DownloadDialog(prompt, ::close)
        is Prompt.Permission -> PermissionDialog(prompt, ::close)
        is Prompt.External -> BrisaDialog(
            onDismissRequest = ::close,
            title = { Text(stringResource(R.string.external_title)) },
            text = { Text(prompt.message) },
            confirmButton = {
                TextButton(onClick = {
                    close()
                    prompt.onOpen()
                }) { Text(stringResource(R.string.external_open)) }
            },
            dismissButton = { TextButton(onClick = ::close) { Text(stringResource(android.R.string.cancel)) } },
        )
        is Prompt.Auth -> AuthDialog(prompt)
        is Prompt.LinkMenu -> BrisaDialog(
            onDismissRequest = ::close,
            title = { Text(prompt.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
            text = {
                Column {
                    prompt.actions.forEach { (label, action) ->
                        Text(
                            label,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    close()
                                    action()
                                }
                                .padding(vertical = 10.dp),
                        )
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = ::close) { Text(stringResource(android.R.string.cancel)) } },
        )
    }
}

@Composable
private fun DownloadDialog(prompt: Prompt.Download, close: () -> Unit) {
    var name by remember { mutableStateOf(prompt.name) }
    BrisaDialog(
        onDismissRequest = close,
        title = { Text(stringResource(R.string.download_title)) },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.file_name)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(prompt.message, modifier = Modifier.padding(top = 12.dp))
            }
        },
        confirmButton = {
            TextButton(onClick = {
                close()
                prompt.onConfirm(name)
            }) { Text(stringResource(R.string.download)) }
        },
        dismissButton = {
            Row {
                TextButton(onClick = {
                    close()
                    prompt.onChangeFolder(name)
                }) { Text(stringResource(R.string.change_folder)) }
                TextButton(onClick = close) { Text(stringResource(android.R.string.cancel)) }
            }
        },
    )
}

@Composable
private fun PermissionDialog(prompt: Prompt.Permission, close: () -> Unit) {
    var keep by remember { mutableStateOf(true) }
    BrisaDialog(
        onDismissRequest = {
            close()
            prompt.onDecision(false, false)
        },
        title = { Text(stringResource(R.string.perm_title)) },
        text = {
            Column {
                Text(prompt.message)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp)
                        .clickable { keep = !keep },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = keep, onCheckedChange = null)
                    Text(stringResource(R.string.perm_remember), Modifier.padding(start = 12.dp))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                close()
                prompt.onDecision(true, keep)
            }) { Text(stringResource(R.string.perm_allow)) }
        },
        dismissButton = {
            TextButton(onClick = {
                close()
                prompt.onDecision(false, keep)
            }) { Text(stringResource(R.string.perm_block)) }
        },
    )
}

@Composable
private fun AuthDialog(prompt: Prompt.Auth) {
    val scheme = MaterialTheme.colorScheme
    var user by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var visible by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    BrisaDialog(
        onDismissRequest = prompt.onCancel,
        title = { Text(stringResource(R.string.auth_title)) },
        text = {
            Column {
                Text(prompt.message, color = scheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                if (prompt.insecure) {
                    Text(
                        stringResource(R.string.auth_insecure),
                        color = scheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                OutlinedTextField(
                    value = user,
                    onValueChange = { user = it },
                    label = { Text(stringResource(R.string.auth_username)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 16.dp).focusRequester(focus),
                )
                OutlinedTextField(
                    value = pass,
                    onValueChange = { pass = it },
                    label = { Text(stringResource(R.string.auth_password)) },
                    singleLine = true,
                    visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    trailingIcon = {
                        IconButton(onClick = { visible = !visible }) {
                            Icon(
                                painterResource(if (visible) R.drawable.ic_visibility_off else R.drawable.ic_visibility),
                                contentDescription = stringResource(if (visible) R.string.hide_password else R.string.show_password),
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { prompt.onSubmit(user, pass) }) { Text(stringResource(R.string.auth_sign_in)) }
        },
        dismissButton = {
            TextButton(onClick = prompt.onCancel) { Text(stringResource(android.R.string.cancel)) }
        },
    )
}

// endregion
