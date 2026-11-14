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
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.videofetch.pro.BuildConfig
import com.videofetch.pro.R
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class AppPage { HOME, DOWNLOADS, SETTINGS }

private sealed interface ParseUiState {
    data object Idle : ParseUiState
    data object Loading : ParseUiState
    data class Failed(val message: String) : ParseUiState
    data class Ready(val result: LocalMediaResolution.Resolved) : ParseUiState
}

@Composable
fun VideoFetchApp(sharedText: String? = null) {
    var page by rememberSaveable { mutableStateOf(AppPage.HOME) }
    val context = LocalContext.current
    val downloadManager = remember(context) {
        LocalDownloadManager(context, EncryptedLocalSessionStore(context))
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        when (page) {
            AppPage.HOME -> HomeScreen(
                modifier = Modifier.padding(padding),
                initialUrl = sharedText.orEmpty(),
                downloadManager = downloadManager,
                onOpenDownloads = { page = AppPage.DOWNLOADS },
                onOpenSettings = { page = AppPage.SETTINGS }
            )
            AppPage.DOWNLOADS -> DownloadsScreen(
                modifier = Modifier.padding(padding),
                downloadManager = downloadManager,
                onBack = { page = AppPage.HOME }
            )
            AppPage.SETTINGS -> SettingsScreen(
                modifier = Modifier.padding(padding),
                onBack = { page = AppPage.HOME }
            )
        }
    }
}

@Composable
private fun HomeScreen(
    modifier: Modifier = Modifier,
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

    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 22.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                LogoMark(size = 34)
                Spacer(Modifier.width(9.dp))
                Text("VideoFetch Pro", fontWeight = FontWeight.Bold, fontSize = 16.sp)
            }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = onOpenDownloads) {
                Icon(Icons.Filled.History, contentDescription = stringResource(R.string.downloads_title))
            }
            IconButton(onClick = onOpenSettings) {
                Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.settings_title))
            }
        }

        Spacer(Modifier.height(50.dp))
        LogoMark(size = 68)
        Spacer(Modifier.height(15.dp))
        Text("VideoFetch Pro", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.ExtraBold)
        Spacer(Modifier.height(5.dp))
        Text(
            stringResource(R.string.guest_mode_label),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall
        )
        Spacer(Modifier.height(34.dp))

        OutlinedTextField(
            value = sourceUrl,
            onValueChange = {
                sourceUrl = it
                parseState = ParseUiState.Idle
            },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text(stringResource(R.string.link_placeholder)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            shape = RoundedCornerShape(17.dp),
            trailingIcon = {
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
                    Icon(Icons.Filled.ContentPaste, contentDescription = stringResource(R.string.paste_button))
                }
            }
        )

        Spacer(Modifier.height(12.dp))
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
            modifier = Modifier.fillMaxWidth().height(52.dp),
            shape = RoundedCornerShape(16.dp)
        ) {
            if (parseState is ParseUiState.Loading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary
                )
                Spacer(Modifier.width(10.dp))
                Text(stringResource(R.string.parsing_button))
            } else {
                Icon(Icons.Filled.PlayArrow, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.parse_button), fontWeight = FontWeight.SemiBold)
            }
        }

        if (pastedHint) {
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.clipboard_pasted), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelSmall)
        }

        when (val state = parseState) {
            ParseUiState.Idle, ParseUiState.Loading -> Unit
            is ParseUiState.Failed -> {
                Spacer(Modifier.height(16.dp))
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Text(
                        state.message,
                        modifier = Modifier.padding(14.dp),
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
            is ParseUiState.Ready -> {
                Spacer(Modifier.height(22.dp))
                VideoResultCard(
                    result = state.result,
                    selectedFormatId = selectedFormatId,
                    onSelectFormat = { selectedFormatId = it },
                    onDownload = { format ->
                        val message = runCatching {
                            downloadManager.enqueue(
                                state.result.providerId,
                                state.result.allowedHosts,
                                format.toDownloadable()
                            )
                        }
                        scope.launch {
                            val text = if (message.isSuccess) context.getString(R.string.download_added)
                            else context.getString(R.string.download_enqueue_failed)
                            if (message.isSuccess) onOpenDownloads()
                            else parseState = ParseUiState.Failed(text)
                        }
                    }
                )
            }
        }
        Spacer(Modifier.height(32.dp))
    }
}

@Composable
private fun LogoMark(size: Int) {
    Surface(
        modifier = Modifier.size(size.dp),
        color = MaterialTheme.colorScheme.primary,
        shape = RoundedCornerShape((size * 0.28f).dp)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                Icons.Filled.PlayArrow,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size((size * 0.72f).dp)
            )
        }
    }
}

@Composable
private fun VideoResultCard(
    result: LocalMediaResolution.Resolved,
    selectedFormatId: String,
    onSelectFormat: (String) -> Unit,
    onDownload: (LocalMediaFormat) -> Unit
) {
    val video = result.video
    val selectedFormat = video.formats.firstOrNull { it.id == selectedFormatId } ?: video.formats.firstOrNull()
    var formatMenuExpanded by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column {
            val safeCover = video.coverUrl?.takeIf { it.startsWith("https://", ignoreCase = true) }
            if (safeCover != null) {
                AsyncImage(
                    model = safeCover,
                    contentDescription = video.title,
                    modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f),
                    contentScale = ContentScale.Crop
                )
            } else {
                Box(
                    modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f)
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Filled.PlayArrow,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(52.dp)
                    )
                }
            }
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(9.dp)
            ) {
                Text(video.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                val details = listOfNotNull(
                    video.uploader?.takeIf { it.isNotBlank() },
                    video.durationSeconds?.takeIf { it > 0 }?.let(::formatDuration),
                    video.platformName.takeIf { it.isNotBlank() }
                )
                if (details.isNotEmpty()) {
                    Text(
                        details.joinToString("  ·  "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                HorizontalDivider()
                Text(stringResource(R.string.available_formats), fontWeight = FontWeight.SemiBold)
                if (video.formats.isEmpty()) {
                    Text(
                        stringResource(R.string.no_download_formats),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Box {
                        OutlinedButton(
                            onClick = { formatMenuExpanded = true },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(13.dp)
                        ) {
                            Text(selectedFormat?.label ?: stringResource(R.string.select_format))
                        }
                        DropdownMenu(
                            expanded = formatMenuExpanded,
                            onDismissRequest = { formatMenuExpanded = false }
                        ) {
                            video.formats.forEach { format ->
                                DropdownMenuItem(
                                    text = { Text(format.label) },
                                    onClick = {
                                        onSelectFormat(format.id)
                                        formatMenuExpanded = false
                                    }
                                )
                            }
                        }
                    }
                    selectedFormat?.details()?.takeIf { it.isNotBlank() }?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Button(
                        onClick = { selectedFormat?.let(onDownload) },
                        enabled = selectedFormat != null,
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Icon(Icons.Filled.Download, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.download_selected_format))
                    }
                }
            }
        }
    }
}

private fun formatDuration(seconds: Long): String {
    val hours = seconds / 3600
    val minutes = (seconds % 3600) / 60
    val remainder = seconds % 60
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, remainder)
    else "%d:%02d".format(minutes, remainder)
}

@Composable
private fun DownloadsScreen(
    modifier: Modifier = Modifier,
    downloadManager: LocalDownloadManager,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    var records by remember { mutableStateOf<List<LocalDownloadRecord>>(emptyList()) }
    var refreshKey by remember { mutableIntStateOf(0) }

    LaunchedEffect(refreshKey) {
        records = withContext(Dispatchers.IO) { downloadManager.list() }
    }

    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        PageHeader(stringResource(R.string.downloads_title), onBack) {
            TextButton(onClick = { refreshKey++ }) { Text(stringResource(R.string.refresh_button)) }
        }
        if (records.isEmpty()) {
            Text(
                stringResource(R.string.downloads_empty),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                stringResource(R.string.downloads_empty_body),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            records.forEach { record ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(record.title, fontWeight = FontWeight.SemiBold)
                        Text(downloadStatusText(record.status), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (record.totalBytes > 0) {
                            val progress = record.downloadedBytes.toFloat() / record.totalBytes.toFloat()
                            LinearProgressIndicator(progress = { progress.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                            Text("${(progress * 100).toInt()}%", style = MaterialTheme.typography.labelSmall)
                        }
                        if (record.status == DownloadManager.STATUS_SUCCESSFUL) {
                            OutlinedButton(onClick = {
                                val uri = downloadManager.uriFor(record.id)
                                if (uri != null) {
                                    val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, "video/*")
                                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    runCatching { context.startActivity(intent) }
                                }
                            }) { Text(stringResource(R.string.open_file_button)) }
                        }
                    }
                }
            }
        }
        Text(stringResource(R.string.local_download_location), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun downloadStatusText(status: Int): String = stringResource(
    when (status) {
        DownloadManager.STATUS_PENDING -> R.string.download_status_pending
        DownloadManager.STATUS_RUNNING -> R.string.download_status_running
        DownloadManager.STATUS_PAUSED -> R.string.download_status_paused
        DownloadManager.STATUS_SUCCESSFUL -> R.string.download_status_success
        DownloadManager.STATUS_FAILED -> R.string.download_status_failed
        else -> R.string.download_status_unknown
    }
)

@Composable
private fun SettingsScreen(
    modifier: Modifier = Modifier,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val sessions = remember(context) { EncryptedLocalSessionStore(context) }
    val qualityStore = remember(context) { VideoQualityPreferenceStore(context) }
    var qualityValue by rememberSaveable { mutableStateOf(qualityStore.get().storageValue) }
    var qualityMenuExpanded by remember { mutableStateOf(false) }

    var providerId by rememberSaveable { mutableStateOf("my_platform") }
    var host by rememberSaveable { mutableStateOf("") }
    var cookieHeader by remember { mutableStateOf("") }
    var hasSavedCookie by remember { mutableStateOf(false) }
    var savedHost by remember { mutableStateOf<String?>(null) }
    var sessionMessage by remember { mutableStateOf("") }
    val quality = VideoQualityPreference.fromStorage(qualityValue)

    LaunchedEffect(providerId) {
        val profile = runCatching { sessions.profile(providerId) }.getOrNull()
        hasSavedCookie = profile != null
        savedHost = profile?.host
    }

    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(15.dp)
    ) {
        PageHeader(stringResource(R.string.settings_title), onBack)

        Card(shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Column(Modifier.padding(17.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                Text(stringResource(R.string.quality_title), fontWeight = FontWeight.SemiBold)
                Box {
                    OutlinedButton(onClick = { qualityMenuExpanded = true }) {
                        Text(stringResource(quality.labelRes))
                    }
                    DropdownMenu(expanded = qualityMenuExpanded, onDismissRequest = { qualityMenuExpanded = false }) {
                        VideoQualityPreference.entries.forEach { option ->
                            DropdownMenuItem(
                                text = { Text(stringResource(option.labelRes)) },
                                onClick = {
                                    qualityValue = option.storageValue
                                    qualityStore.set(option)
                                    qualityMenuExpanded = false
                                }
                            )
                        }
                    }
                }
                Text(stringResource(R.string.quality_explainer), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(stringResource(R.string.quality_format_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        Card(shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Column(Modifier.padding(17.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                Text(stringResource(R.string.local_session_title), fontWeight = FontWeight.SemiBold)
                Text(stringResource(R.string.local_session_explainer), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(
                    value = providerId,
                    onValueChange = { providerId = it.lowercase(); sessionMessage = "" },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.provider_id_label)) },
                    singleLine = true
                )
                OutlinedTextField(
                    value = host,
                    onValueChange = { host = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.cookie_host_label)) },
                    placeholder = { Text("www.example.com") },
                    singleLine = true
                )
                OutlinedTextField(
                    value = cookieHeader,
                    onValueChange = { cookieHeader = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.cookie_value_label)) },
                    placeholder = { Text("session=value; token=value") },
                    minLines = 2,
                    maxLines = 4,
                    visualTransformation = PasswordVisualTransformation()
                )
                if (hasSavedCookie) {
                    Text(stringResource(R.string.local_session_saved, savedHost.orEmpty()), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
                }
                if (sessionMessage.isNotBlank()) {
                    Text(sessionMessage, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
                }
                Button(
                    onClick = {
                        val saved = runCatching { sessions.saveCookie(providerId, host, cookieHeader) }
                        if (saved.isSuccess) {
                            cookieHeader = ""
                            hasSavedCookie = true
                            savedHost = runCatching { sessions.profile(providerId)?.host }.getOrNull()
                            sessionMessage = context.getString(R.string.local_session_save_success)
                        } else {
                            sessionMessage = saved.exceptionOrNull()?.message
                                ?: context.getString(R.string.local_session_save_error)
                        }
                    },
                    enabled = providerId.isNotBlank() && host.isNotBlank() && cookieHeader.isNotBlank(),
                    modifier = Modifier.fillMaxWidth()
                ) { Text(stringResource(R.string.save_local_session)) }
                OutlinedButton(
                    onClick = {
                        runCatching { sessions.clearCookie(providerId) }
                        hasSavedCookie = false
                        savedHost = null
                        cookieHeader = ""
                        sessionMessage = context.getString(R.string.local_session_cleared)
                    },
                    enabled = hasSavedCookie,
                    modifier = Modifier.fillMaxWidth()
                ) { Text(stringResource(R.string.clear_local_session)) }
                Text(stringResource(R.string.cookie_security_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
        }
        Text(
            stringResource(R.string.app_build_version, BuildConfig.VERSION_NAME),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun PageHeader(
    title: String,
    onBack: () -> Unit,
    action: @Composable RowScope.() -> Unit = {}
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.Filled.ArrowBack, contentDescription = stringResource(R.string.back_button))
        }
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Spacer(Modifier.weight(1f))
        action()
    }
}
