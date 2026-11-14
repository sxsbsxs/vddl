package com.videofetch.pro.ui

import android.app.DownloadManager
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.videofetch.pro.BuildConfig
import com.videofetch.pro.R
import com.videofetch.pro.core.CookieProviderDetector
import com.videofetch.pro.core.SourceUrlValidator
import com.videofetch.pro.data.EncryptedLocalSessionStore
import com.videofetch.pro.data.LocalDownloadManager
import com.videofetch.pro.data.LocalDownloadRecord
import com.videofetch.pro.data.LocalMediaFormat
import com.videofetch.pro.data.LocalMediaProviderRegistry
import com.videofetch.pro.data.LocalMediaResolution
import com.videofetch.pro.data.VideoQualityPreference
import com.videofetch.pro.data.VideoQualityPreferenceStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class AppPage { HOME, DOWNLOADS, SETTINGS }

private sealed interface ParseUiState {
    data object Idle : ParseUiState
    data object Loading : ParseUiState
    data class Failed(val message: String) : ParseUiState
    data class Ready(val result: LocalMediaResolution.Resolved) : ParseUiState
}

private val Canvas = Color(0xFFF4F5FA)
private val Ink = Color(0xFF1D2340)
private val Muted = Color(0xFF7C829A)
private val Violet = Color(0xFF5755D9)
private val Teal = Color(0xFF13A89E)
private val Line = Color(0xFFE8EAF2)

@Composable
fun VideoFetchApp(sharedText: String? = null) {
    var page by rememberSaveable { mutableStateOf(AppPage.HOME) }
    val context = LocalContext.current
    val sessions = remember(context) { EncryptedLocalSessionStore(context) }
    val downloadManager = remember(context) { LocalDownloadManager(context, sessions) }

    MaterialTheme(
        colorScheme = MaterialTheme.colorScheme.copy(
            primary = Violet,
            secondary = Teal,
            background = Canvas,
            surface = Color.White,
            onSurface = Ink
        )
    ) {
        Surface(Modifier.fillMaxSize(), color = Canvas) {
            when (page) {
                AppPage.HOME -> HomeScreen(
                    initialUrl = sharedText.orEmpty(),
                    downloadManager = downloadManager,
                    onOpenDownloads = { page = AppPage.DOWNLOADS },
                    onOpenSettings = { page = AppPage.SETTINGS }
                )
                AppPage.DOWNLOADS -> DownloadsScreen(downloadManager) { page = AppPage.HOME }
                AppPage.SETTINGS -> SettingsScreen { page = AppPage.HOME }
            }
        }
    }
}

@Composable
private fun HomeScreen(
    initialUrl: String,
    downloadManager: LocalDownloadManager,
    onOpenDownloads: () -> Unit,
    onOpenSettings: () -> Unit
) {
    val context = LocalContext.current
    val sessions = remember(context) { EncryptedLocalSessionStore(context) }
    val qualityStore = remember(context) { VideoQualityPreferenceStore(context) }
    val scope = rememberCoroutineScope()
    var sourceUrl by rememberSaveable { mutableStateOf(initialUrl) }
    var parseState by remember { mutableStateOf<ParseUiState>(ParseUiState.Idle) }
    var selectedFormatId by rememberSaveable { mutableStateOf("") }
    var pastedHint by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        HomeTopBar(onOpenDownloads, onOpenSettings)
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp).padding(bottom = 30.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            HeroPanel()
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SectionHeading(stringResource(R.string.home_link_title), stringResource(R.string.home_link_subtitle))
                Card(
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.White),
                    elevation = CardDefaults.cardElevation(2.dp)
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedTextField(
                            value = sourceUrl,
                            onValueChange = { sourceUrl = it; parseState = ParseUiState.Idle },
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = { Text(stringResource(R.string.link_placeholder), color = Muted) },
                            leadingIcon = { Icon(Icons.Filled.PlayArrow, contentDescription = null, tint = Violet) },
                            trailingIcon = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    if (sourceUrl.isNotBlank()) {
                                        IconButton(onClick = { sourceUrl = ""; parseState = ParseUiState.Idle }) {
                                            Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.clear_button), tint = Muted)
                                        }
                                    }
                                    IconButton(onClick = {
                                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                        val pasted = clipboard.primaryClip?.takeIf { it.itemCount > 0 }
                                            ?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
                                        if (pasted.isNotBlank()) {
                                            sourceUrl = pasted
                                            parseState = ParseUiState.Idle
                                            pastedHint = true
                                        }
                                    }) {
                                        Icon(Icons.Filled.ContentPaste, contentDescription = stringResource(R.string.paste_button), tint = Violet)
                                    }
                                }
                            },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                            shape = RoundedCornerShape(16.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Violet,
                                unfocusedBorderColor = Line,
                                focusedContainerColor = Color(0xFFFBFBFE),
                                unfocusedContainerColor = Color(0xFFFBFBFE)
                            )
                        )
                        Button(
                            onClick = {
                                pastedHint = false
                                if (!SourceUrlValidator.isPotentiallyValid(sourceUrl)) {
                                    parseState = ParseUiState.Failed(context.getString(R.string.link_invalid))
                                } else {
                                    scope.launch {
                                        parseState = ParseUiState.Loading
                                        val provider = LocalMediaProviderRegistry.findFor(sourceUrl.trim())
                                        if (provider == null) {
                                            parseState = ParseUiState.Failed(context.getString(R.string.parse_provider_unavailable))
                                            return@launch
                                        }
                                        val resolved = runCatching {
                                            withContext(Dispatchers.IO) {
                                                provider.resolve(sourceUrl.trim(), qualityStore.get(), sessions)
                                            }
                                        }.getOrElse { error ->
                                            parseState = ParseUiState.Failed(
                                                error.message?.takeIf { it.isNotBlank() }
                                                    ?: context.getString(R.string.parse_failed)
                                            )
                                            return@launch
                                        }
                                        parseState = when (resolved) {
                                            is LocalMediaResolution.Resolved -> {
                                                selectedFormatId = resolved.video.formats.firstOrNull()?.id.orEmpty()
                                                ParseUiState.Ready(resolved)
                                            }
                                            is LocalMediaResolution.Unsupported -> ParseUiState.Failed(resolved.reason)
                                            is LocalMediaResolution.Available -> ParseUiState.Failed(context.getString(R.string.parse_incomplete_result))
                                        }
                                    }
                                }
                            },
                            enabled = sourceUrl.isNotBlank() && parseState !is ParseUiState.Loading,
                            modifier = Modifier.fillMaxWidth().height(54.dp),
                            shape = RoundedCornerShape(16.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Violet)
                        ) {
                            if (parseState is ParseUiState.Loading) {
                                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = Color.White)
                                Spacer(Modifier.width(10.dp))
                                Text(stringResource(R.string.parsing_button), fontWeight = FontWeight.SemiBold)
                            } else {
                                Icon(Icons.Filled.PlayArrow, contentDescription = null)
                                Spacer(Modifier.width(8.dp))
                                Text(stringResource(R.string.parse_button), fontSize = 16.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                        if (pastedHint) {
                            Text(stringResource(R.string.clipboard_pasted), color = Violet, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
                Text(
                    stringResource(R.string.home_link_helper),
                    modifier = Modifier.padding(horizontal = 4.dp),
                    color = Muted,
                    style = MaterialTheme.typography.bodySmall
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SectionHeading(stringResource(R.string.home_platform_title), stringResource(R.string.home_platform_subtitle))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    PlatformPill("Bilibili", "B", Color(0xFF00A1D6), Modifier.weight(1f))
                    PlatformPill("YouTube", "▶", Color(0xFFE53935), Modifier.weight(1f))
                    PlatformPill("Niconico", "N", Color(0xFF525B76), Modifier.weight(1f))
                }
            }

            when (val state = parseState) {
                ParseUiState.Idle -> Unit
                ParseUiState.Loading -> Surface(shape = RoundedCornerShape(18.dp), color = Color.White) {
                    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp, color = Violet)
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(stringResource(R.string.parsing_button), fontWeight = FontWeight.SemiBold, color = Ink)
                            Text(stringResource(R.string.parsing_hint), color = Muted, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                is ParseUiState.Failed -> Surface(shape = RoundedCornerShape(18.dp), color = Color(0xFFFFF0F0)) {
                    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.Top) {
                        Icon(Icons.Filled.Info, contentDescription = null, tint = Color(0xFFCB4545))
                        Spacer(Modifier.width(10.dp))
                        Text(state.message, color = Color(0xFF8E3030), style = MaterialTheme.typography.bodySmall)
                    }
                }
                is ParseUiState.Ready -> VideoResultCard(
                    result = state.result,
                    selectedFormatId = selectedFormatId,
                    onSelectFormat = { selectedFormatId = it },
                    onDownload = { format ->
                        runCatching {
                            downloadManager.enqueue(state.result.providerId, state.result.allowedHosts, format.toDownloadable())
                        }.onSuccess { onOpenDownloads() }
                            .onFailure { parseState = ParseUiState.Failed(context.getString(R.string.download_enqueue_failed)) }
                    }
                )
            }
            FooterNote()
        }
    }
}

@Composable
private fun HomeTopBar(onDownloads: () -> Unit, onSettings: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        BrandMark(42)
        Spacer(Modifier.width(10.dp))
        Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text("VideoFetch", color = Ink, fontWeight = FontWeight.ExtraBold, fontSize = 17.sp)
            Text("PRO", color = Violet, fontWeight = FontWeight.Black, fontSize = 9.sp, letterSpacing = 2.sp)
        }
        Spacer(Modifier.weight(1f))
        RoundIconButton(Icons.Filled.History, stringResource(R.string.downloads_title), onDownloads)
        Spacer(Modifier.width(8.dp))
        RoundIconButton(Icons.Filled.Settings, stringResource(R.string.settings_title), onSettings)
    }
}

@Composable
private fun HeroPanel() {
    Box(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp))
            .background(Brush.linearGradient(listOf(Color(0xFF202A61), Color(0xFF4D49B8), Color(0xFF7667E9))))
            .padding(22.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Surface(color = Color.White.copy(alpha = 0.14f), shape = CircleShape) {
                Text(stringResource(R.string.guest_mode_label), Modifier.padding(horizontal = 12.dp, vertical = 7.dp), color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
            }
            Text(stringResource(R.string.home_hero_title), color = Color.White, fontSize = 26.sp, lineHeight = 32.sp, fontWeight = FontWeight.ExtraBold)
            Text(stringResource(R.string.home_hero_subtitle), color = Color.White.copy(alpha = 0.78f), style = MaterialTheme.typography.bodyMedium, lineHeight = 21.sp)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(7.dp).background(Color(0xFF5BE0B4), CircleShape))
                Spacer(Modifier.width(7.dp))
                Text(stringResource(R.string.home_local_processing), color = Color.White.copy(alpha = 0.9f), style = MaterialTheme.typography.labelSmall)
            }
        }
        Box(Modifier.align(Alignment.TopEnd).offset(x = 8.dp, y = (-8).dp).size(84.dp).background(Color.White.copy(alpha = 0.07f), CircleShape))
        Box(Modifier.align(Alignment.BottomEnd).offset(x = 34.dp, y = 38.dp).size(108.dp).background(Color.White.copy(alpha = 0.06f), CircleShape))
    }
}

@Composable
private fun BrandMark(size: Int) {
    Box(
        Modifier.size(size.dp).clip(RoundedCornerShape((size * 0.30f).dp))
            .background(Brush.linearGradient(listOf(Violet, Color(0xFF8172F3)))),
        contentAlignment = Alignment.Center
    ) {
        Icon(Icons.Filled.PlayArrow, contentDescription = null, tint = Color.White, modifier = Modifier.size((size * 0.66f).dp))
    }
}

@Composable
private fun RoundIconButton(icon: androidx.compose.ui.graphics.vector.ImageVector, description: String, onClick: () -> Unit) {
    Surface(shape = CircleShape, color = Color.White, shadowElevation = 1.dp) {
        IconButton(onClick = onClick, modifier = Modifier.size(42.dp)) {
            Icon(icon, contentDescription = description, tint = Ink)
        }
    }
}

@Composable
private fun SectionHeading(title: String, subtitle: String) {
    Column(Modifier.padding(horizontal = 2.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(title, color = Ink, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
        Text(subtitle, color = Muted, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun PlatformPill(label: String, mark: String, color: Color, modifier: Modifier = Modifier) {
    Surface(modifier, shape = RoundedCornerShape(15.dp), color = Color.White) {
        Row(Modifier.padding(horizontal = 9.dp, vertical = 11.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(22.dp).clip(CircleShape).background(color), contentAlignment = Alignment.Center) {
                Text(mark, fontSize = 10.sp, fontWeight = FontWeight.Black, color = Color.White)
            }
            Spacer(Modifier.width(6.dp))
            Text(label, color = Ink, fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        }
    }
}

@Composable
private fun VideoResultCard(result: LocalMediaResolution.Resolved, selectedFormatId: String, onSelectFormat: (String) -> Unit, onDownload: (LocalMediaFormat) -> Unit) {
    val video = result.video
    val selectedFormat = video.formats.firstOrNull { it.id == selectedFormatId } ?: video.formats.firstOrNull()
    var expanded by remember { mutableStateOf(false) }
    Card(shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = Color.White), elevation = CardDefaults.cardElevation(2.dp)) {
        Column {
            Box {
                val cover = video.coverUrl?.takeIf { it.startsWith("https://", true) }
                if (cover != null) {
                    AsyncImage(model = cover, contentDescription = video.title, modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f), contentScale = ContentScale.Crop)
                } else {
                    Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(Color(0xFFE9EAF5)), contentAlignment = Alignment.Center) { BrandMark(58) }
                }
                Surface(Modifier.align(Alignment.BottomStart).padding(14.dp), color = Color.Black.copy(alpha = 0.62f), shape = CircleShape) {
                    Text(video.platformName, color = Color.White, modifier = Modifier.padding(horizontal = 11.dp, vertical = 6.dp), fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                }
            }
            Column(Modifier.padding(17.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(video.title, color = Ink, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, maxLines = 3, overflow = TextOverflow.Ellipsis)
                val meta = listOfNotNull(video.uploader?.takeIf { it.isNotBlank() }, video.durationSeconds?.takeIf { it > 0 }?.let(::formatDuration))
                if (meta.isNotEmpty()) Text(meta.joinToString("   ·   "), color = Muted, style = MaterialTheme.typography.bodySmall)
                HorizontalDivider(color = Line)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.available_formats), color = Ink, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    Surface(color = Color(0xFFF1F0FF), shape = CircleShape) {
                        Text(if (video.formats.isNotEmpty()) stringResource(R.string.download_ready) else stringResource(R.string.metadata_only), Modifier.padding(horizontal = 10.dp, vertical = 5.dp), color = Violet, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    }
                }
                if (video.availableVariantLabels.isNotEmpty()) {
                    Text(stringResource(R.string.provider_reported_formats), color = Muted, style = MaterialTheme.typography.labelSmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                        video.availableVariantLabels.take(4).forEach { label ->
                            Surface(color = Color(0xFFF2F4FA), shape = RoundedCornerShape(9.dp)) {
                                Text(label, Modifier.padding(horizontal = 9.dp, vertical = 6.dp), color = Ink, fontSize = 11.sp)
                            }
                        }
                    }
                }
                if (video.formats.isEmpty()) {
                    Text(video.downloadNoteResId?.let { stringResource(it) } ?: stringResource(R.string.no_download_formats), color = Muted, style = MaterialTheme.typography.bodySmall, lineHeight = 19.sp)
                } else {
                    Box {
                        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp), colors = ButtonDefaults.outlinedButtonColors(contentColor = Ink)) {
                            Text(selectedFormat?.label ?: stringResource(R.string.select_format), modifier = Modifier.weight(1f))
                            Text("⌄", color = Violet, fontSize = 17.sp)
                        }
                        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                            video.formats.forEach { format ->
                                DropdownMenuItem(
                                    text = { Column { Text(format.label, fontWeight = FontWeight.SemiBold); format.details().takeIf { it.isNotBlank() }?.let { Text(it, color = Muted, style = MaterialTheme.typography.labelSmall) } } },
                                    onClick = { onSelectFormat(format.id); expanded = false }
                                )
                            }
                        }
                    }
                    Button(onClick = { selectedFormat?.let(onDownload) }, enabled = selectedFormat != null, modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(15.dp), colors = ButtonDefaults.buttonColors(containerColor = Violet)) {
                        Icon(Icons.Filled.Download, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.download_selected_format), fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
private fun DownloadsScreen(downloadManager: LocalDownloadManager, onBack: () -> Unit) {
    val context = LocalContext.current
    var records by remember { mutableStateOf<List<LocalDownloadRecord>>(emptyList()) }
    var refreshKey by remember { mutableIntStateOf(0) }
    var filter by rememberSaveable { mutableStateOf("all") }
    var actionMessage by remember { mutableStateOf("") }
    LaunchedEffect(refreshKey) {
        while (true) {
            records = withContext(Dispatchers.IO) { downloadManager.list() }
            delay(1000)
        }
    }

    val active = records.count { it.status == DownloadManager.STATUS_PENDING || it.status == DownloadManager.STATUS_RUNNING || it.status == DownloadManager.STATUS_PAUSED }
    val done = records.count { it.status == DownloadManager.STATUS_SUCCESSFUL }
    val filtered = records.filter {
        when (filter) {
            "active" -> it.status == DownloadManager.STATUS_PENDING || it.status == DownloadManager.STATUS_RUNNING || it.status == DownloadManager.STATUS_PAUSED
            "done" -> it.status == DownloadManager.STATUS_SUCCESSFUL
            else -> true
        }
    }
    Column(Modifier.fillMaxSize()) {
        PageTopBar(stringResource(R.string.downloads_title), stringResource(R.string.downloads_subtitle), onBack) {
            RoundIconButton(Icons.Filled.Refresh, stringResource(R.string.refresh_button)) { refreshKey++ }
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MetricCard(stringResource(R.string.active_downloads), active.toString(), Violet, Modifier.weight(1f))
                MetricCard(stringResource(R.string.completed_downloads), done.toString(), Teal, Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = filter == "all", onClick = { filter = "all" }, label = { Text(stringResource(R.string.filter_all)) })
                FilterChip(selected = filter == "active", onClick = { filter = "active" }, label = { Text(stringResource(R.string.filter_active)) })
                FilterChip(selected = filter == "done", onClick = { filter = "done" }, label = { Text(stringResource(R.string.filter_done)) })
            }
            if (actionMessage.isNotBlank()) Text(actionMessage, color = Violet, style = MaterialTheme.typography.bodySmall)
            if (filtered.isEmpty()) {
                EmptyPanel(stringResource(R.string.downloads_empty), stringResource(R.string.downloads_empty_body))
            } else {
                filtered.forEach { record ->
                    DownloadCard(
                        record = record,
                        onOpen = {
                            val uri = downloadManager.uriFor(record)
                            if (uri != null) {
                                val intent = Intent(Intent.ACTION_VIEW).apply {
                                    setDataAndType(uri, "video/*")
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                runCatching { context.startActivity(intent) }
                            }
                        },
                        onRemove = {
                            val removed = downloadManager.remove(record)
                            actionMessage = if (removed) context.getString(R.string.download_removed) else context.getString(R.string.download_remove_failed)
                            refreshKey++
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun DownloadCard(record: LocalDownloadRecord, onOpen: () -> Unit, onRemove: () -> Unit) {
    val context = LocalContext.current
    val done = record.status == DownloadManager.STATUS_SUCCESSFUL
    val failed = record.status == DownloadManager.STATUS_FAILED
    val active = !done && !failed
    val ratio = if (record.totalBytes > 0) (record.downloadedBytes.toFloat() / record.totalBytes).coerceIn(0f, 1f) else 0f
    Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = Color.White)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(42.dp).clip(RoundedCornerShape(13.dp)).background(if (done) Color(0xFFE4F8F2) else Color(0xFFF0EFFF)), contentAlignment = Alignment.Center) {
                    Icon(if (done) Icons.Filled.CheckCircle else Icons.Filled.Download, contentDescription = null, tint = if (done) Teal else Violet)
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(record.title, color = Ink, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(downloadStatusText(record.status, context), color = Muted, style = MaterialTheme.typography.labelSmall)
                }
            }
            if (active && record.totalBytes > 0) {
                LinearProgressIndicator(progress = { ratio }, modifier = Modifier.fillMaxWidth(), color = Violet, trackColor = Color(0xFFECECF4))
                Text("%s / %s · %d%%".format(formatBytes(record.downloadedBytes), formatBytes(record.totalBytes), (ratio * 100).toInt()), color = Muted, style = MaterialTheme.typography.labelSmall)
            } else if (active) {
                LinearProgressIndicator(Modifier.fillMaxWidth(), color = Violet, trackColor = Color(0xFFECECF4))
                Text(stringResource(R.string.download_preparing), color = Muted, style = MaterialTheme.typography.labelSmall)
            } else if (done) {
                Text(stringResource(R.string.download_complete_hint), color = Muted, style = MaterialTheme.typography.labelSmall)
            }
            record.error?.takeIf { failed && it.isNotBlank() }?.let {
                Text(it, color = Color(0xFFB33D3D), style = MaterialTheme.typography.bodySmall)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (done) {
                    Button(onClick = onOpen, modifier = Modifier.weight(1f), shape = RoundedCornerShape(12.dp)) { Text(stringResource(R.string.open_file_button)) }
                }
                OutlinedButton(onClick = onRemove, modifier = Modifier.weight(if (done) 0.8f else 1f), shape = RoundedCornerShape(12.dp)) {
                    Icon(Icons.Filled.Delete, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.remove_button))
                }
            }
        }
    }
}

@Composable
private fun SettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val sessions = remember(context) { EncryptedLocalSessionStore(context) }
    var cookie by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        PageTopBar(
            stringResource(R.string.settings_title),
            stringResource(R.string.settings_subtitle),
            onBack
        )
        Column(
            Modifier.weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Card(
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                elevation = CardDefaults.cardElevation(2.dp)
            ) {
                Column(
                    Modifier.fillMaxWidth().padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Text(
                        stringResource(R.string.cookie_simple_title),
                        color = Ink,
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleLarge
                    )
                    Text(
                        stringResource(R.string.cookie_simple_description),
                        color = Muted,
                        style = MaterialTheme.typography.bodySmall,
                        lineHeight = 19.sp
                    )
                    OutlinedTextField(
                        value = cookie,
                        onValueChange = { cookie = it; status = "" },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.cookie_simple_label)) },
                        placeholder = { Text(stringResource(R.string.cookie_simple_placeholder)) },
                        singleLine = false,
                        minLines = 3,
                        maxLines = 5,
                        visualTransformation = PasswordVisualTransformation(),
                        shape = RoundedCornerShape(16.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Violet,
                            unfocusedBorderColor = Line,
                            focusedContainerColor = Color(0xFFFBFBFE),
                            unfocusedContainerColor = Color(0xFFFBFBFE)
                        )
                    )
                    Button(
                        onClick = {
                            val target = CookieProviderDetector.detect(cookie)
                            if (target == null) {
                                status = context.getString(R.string.cookie_auto_detect_failed)
                            } else {
                                saving = true
                                runCatching {
                                    sessions.saveCookie(target.providerId, target.host, cookie)
                                }.onSuccess {
                                    cookie = ""
                                    status = context.getString(
                                        R.string.cookie_auto_saved,
                                        target.displayName
                                    )
                                }.onFailure {
                                    status = context.getString(R.string.local_session_save_error)
                                }
                                saving = false
                            }
                        },
                        enabled = cookie.isNotBlank() && !saving,
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                        shape = RoundedCornerShape(15.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Violet)
                    ) {
                        if (saving) {
                            CircularProgressIndicator(
                                Modifier.size(19.dp),
                                color = Color.White,
                                strokeWidth = 2.dp
                            )
                        } else {
                            Text(
                                stringResource(R.string.cookie_auto_save_button),
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                    if (status.isNotBlank()) {
                        Text(
                            status,
                            color = if (status == context.getString(R.string.cookie_auto_detect_failed)) {
                                Color(0xFFB33D3D)
                            } else {
                                Teal
                            },
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
            Text(
                stringResource(R.string.cookie_simple_note),
                color = Muted,
                style = MaterialTheme.typography.bodySmall,
                lineHeight = 18.sp,
                modifier = Modifier.padding(horizontal = 4.dp)
            )
        }
    }
}

@Composable
private fun SettingsSection(title: String, subtitle: String, content: @Composable ColumnScope.() -> Unit) {
    Card(shape = RoundedCornerShape(22.dp), colors = CardDefaults.cardColors(containerColor = Color.White)) {
        Column(Modifier.fillMaxWidth().padding(17.dp), verticalArrangement = Arrangement.spacedBy(11.dp)) {
            Text(title, color = Ink, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
            Text(subtitle, color = Muted, style = MaterialTheme.typography.bodySmall, lineHeight = 18.sp)
            content()
        }
    }
}

@Composable
private fun PageTopBar(title: String, subtitle: String, onBack: () -> Unit, action: @Composable RowScope.() -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        RoundIconButton(Icons.Filled.ArrowBack, stringResource(R.string.back_button), onBack)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, color = Ink, fontWeight = FontWeight.ExtraBold, fontSize = 20.sp)
            Text(subtitle, color = Muted, style = MaterialTheme.typography.labelSmall)
        }
        action()
    }
}

@Composable
private fun MetricCard(label: String, value: String, tint: Color, modifier: Modifier = Modifier) {
    Surface(modifier, shape = RoundedCornerShape(18.dp), color = Color.White) {
        Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(value, color = tint, fontSize = 27.sp, fontWeight = FontWeight.ExtraBold)
            Text(label, color = Muted, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun EmptyPanel(title: String, body: String) {
    Surface(shape = RoundedCornerShape(22.dp), color = Color.White) {
        Column(Modifier.fillMaxWidth().padding(vertical = 30.dp, horizontal = 20.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(9.dp)) {
            BrandMark(52)
            Text(title, color = Ink, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
            Text(body, color = Muted, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun FooterNote() {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(15.dp)).background(Color(0xFFECEEF7)).padding(13.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Filled.Info, contentDescription = null, tint = Violet, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(9.dp))
        Text(stringResource(R.string.rights_body), color = Muted, style = MaterialTheme.typography.labelSmall, lineHeight = 16.sp)
    }
}

private fun formatDuration(seconds: Long): String {
    val hours = seconds / 3600
    val minutes = (seconds % 3600) / 60
    val remainder = seconds % 60
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, remainder) else "%d:%02d".format(minutes, remainder)
}

private fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return "%.1f KB".format(kb)
    val mb = kb / 1024.0
    if (mb < 1024) return "%.1f MB".format(mb)
    return "%.2f GB".format(mb / 1024.0)
}

private fun downloadStatusText(status: Int, context: Context): String = when (status) {
    DownloadManager.STATUS_PENDING -> context.getString(R.string.download_status_pending)
    DownloadManager.STATUS_RUNNING -> context.getString(R.string.download_status_running)
    DownloadManager.STATUS_PAUSED -> context.getString(R.string.download_status_paused)
    DownloadManager.STATUS_SUCCESSFUL -> context.getString(R.string.download_status_success)
    DownloadManager.STATUS_FAILED -> context.getString(R.string.download_status_failed)
    else -> context.getString(R.string.download_status_unknown)
}
