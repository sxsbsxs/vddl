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
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.videofetch.pro.BuildConfig
import com.videofetch.pro.R
import com.videofetch.pro.core.SourceUrlValidator
import com.videofetch.pro.data.EncryptedLocalSessionStore
import com.videofetch.pro.data.LocalDownloadManager
import com.videofetch.pro.data.LocalDownloadRecord
import com.videofetch.pro.data.VideoQualityPreference
import com.videofetch.pro.data.VideoQualityPreferenceStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class AppTab { HOME, TASKS, DOWNLOADS, SETTINGS }
private data class TabItem(val tab: AppTab, val labelRes: Int, val icon: ImageVector)

@Composable
fun VideoFetchApp(sharedText: String? = null) {
    var selectedTab by rememberSaveable { mutableStateOf(AppTab.HOME) }
    var sourceUrl by rememberSaveable { mutableStateOf(sharedText.orEmpty()) }
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val tabs = listOf(
        TabItem(AppTab.HOME, R.string.tab_home, Icons.Filled.Home),
        TabItem(AppTab.TASKS, R.string.tab_tasks, Icons.Filled.History),
        TabItem(AppTab.DOWNLOADS, R.string.tab_downloads, Icons.Filled.Download),
        TabItem(AppTab.SETTINGS, R.string.tab_settings, Icons.Filled.Settings)
    )

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(hostState = snackbar) },
        bottomBar = {
            NavigationBar {
                tabs.forEach { item ->
                    NavigationBarItem(
                        selected = selectedTab == item.tab,
                        onClick = { selectedTab = item.tab },
                        icon = { Icon(item.icon, contentDescription = null) },
                        label = { Text(stringResource(item.labelRes)) }
                    )
                }
            }
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).background(MaterialTheme.colorScheme.background)) {
            when (selectedTab) {
                AppTab.HOME -> HomeScreen(
                    sourceUrl = sourceUrl,
                    onSourceUrlChange = { sourceUrl = it },
                    onPaste = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        val pasted = clipboard.primaryClip?.takeIf { it.itemCount > 0 }
                            ?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
                        val message = if (pasted.isBlank()) R.string.clipboard_empty else R.string.clipboard_pasted
                        if (pasted.isNotBlank()) sourceUrl = pasted
                        scope.launch { snackbar.showSnackbar(context.getString(message)) }
                    },
                    onSubmit = {
                        val message = if (SourceUrlValidator.isPotentiallyValid(sourceUrl))
                            R.string.backend_pending else R.string.link_invalid
                        scope.launch { snackbar.showSnackbar(context.getString(message)) }
                    }
                )
                AppTab.TASKS -> EmptyStateScreen(
                    stringResource(R.string.tasks_title),
                    stringResource(R.string.tasks_empty),
                    stringResource(R.string.tasks_empty_body),
                    Icons.Filled.History
                )
                AppTab.DOWNLOADS -> DownloadsScreen()
                AppTab.SETTINGS -> SettingsScreen()
            }
        }
    }
}

@Composable
private fun HomeScreen(
    sourceUrl: String,
    onSourceUrlChange: (String) -> Unit,
    onPaste: () -> Unit,
    onSubmit: () -> Unit
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Surface(Modifier.size(44.dp), shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.primary) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.VideoLibrary, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(25.dp))
                }
            }
            Spacer(Modifier.width(12.dp))
            Column {
                Text("VideoFetch Pro", fontWeight = FontWeight.Bold, fontSize = 19.sp)
                Text(stringResource(R.string.home_eyebrow), color = MaterialTheme.colorScheme.primary, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
            }
            Spacer(Modifier.weight(1f))
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.secondary.copy(alpha = 0.12f)) {
                Text("BETA", Modifier.padding(horizontal = 12.dp, vertical = 7.dp), color = MaterialTheme.colorScheme.secondary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.home_title), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.ExtraBold)
        Text(stringResource(R.string.home_subtitle), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.68f))

        Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(22.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Link, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.link_label), fontWeight = FontWeight.SemiBold)
                }
                OutlinedTextField(
                    value = sourceUrl,
                    onValueChange = onSourceUrlChange,
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text(stringResource(R.string.link_placeholder)) },
                    minLines = 2,
                    maxLines = 4,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    shape = RoundedCornerShape(14.dp)
                )
                OutlinedButton(onClick = onPaste, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(13.dp)) {
                    Icon(Icons.Filled.ContentPaste, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.paste_button))
                }
                Button(onClick = onSubmit, enabled = sourceUrl.isNotBlank(), modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(13.dp)) {
                    Text(stringResource(R.string.submit_button), modifier = Modifier.padding(vertical = 4.dp))
                }
            }
        }

        Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.06f))) {
            Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
                Icon(Icons.Filled.Security, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text(stringResource(R.string.rights_title), fontWeight = FontWeight.Bold)
                    Text(stringResource(R.string.rights_body), color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        Text(stringResource(R.string.share_hint), color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.55f), style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun EmptyStateScreen(title: String, headline: String, body: String, icon: ImageVector) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        if (title.isNotBlank()) Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(52.dp))
        Surface(Modifier.size(76.dp).align(Alignment.CenterHorizontally), shape = CircleShape, color = MaterialTheme.colorScheme.primary.copy(alpha = 0.09f)) {
            Box(contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(34.dp))
            }
        }
        Text(headline, Modifier.align(Alignment.CenterHorizontally), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text(body, Modifier.align(Alignment.CenterHorizontally), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.62f))
    }
}

@Composable
private fun DownloadsScreen() {
    val context = LocalContext.current
    val downloadManager = remember(context) {
        LocalDownloadManager(context, EncryptedLocalSessionStore(context))
    }
    var records by remember { mutableStateOf<List<LocalDownloadRecord>>(emptyList()) }
    var refreshKey by remember { mutableIntStateOf(0) }

    LaunchedEffect(refreshKey) {
        records = withContext(Dispatchers.IO) { downloadManager.list() }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.downloads_title), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { refreshKey++ }) { Text(stringResource(R.string.refresh_button)) }
        }
        if (records.isEmpty()) {
            EmptyStateScreen("", stringResource(R.string.downloads_empty), stringResource(R.string.downloads_empty_body), Icons.Filled.VideoLibrary)
        } else {
            records.forEach { record ->
                Card(
                    Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(record.title, fontWeight = FontWeight.SemiBold)
                        Text(downloadStatusText(record.status), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f))
                        if (record.totalBytes > 0) {
                            val progress = record.downloadedBytes.toFloat() / record.totalBytes.toFloat()
                            LinearProgressIndicator(progress = { progress.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                            Text((progress * 100).toInt().toString() + "%", style = MaterialTheme.typography.labelSmall)
                        }
                        if (record.status == DownloadManager.STATUS_SUCCESSFUL) {
                            Button(onClick = {
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
        Text(stringResource(R.string.local_download_location), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.58f))
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
private fun SettingsScreen() {
    val context = LocalContext.current
    val sessions = remember(context) { EncryptedLocalSessionStore(context) }
    val qualityStore = remember(context) { VideoQualityPreferenceStore(context) }
    var qualityValue by rememberSaveable { mutableStateOf(qualityStore.get().storageValue) }
    var qualityMenuExpanded by remember { mutableStateOf(false) }
    val quality = VideoQualityPreference.fromStorage(qualityValue)

    var providerId by rememberSaveable { mutableStateOf("my_platform") }
    var host by rememberSaveable { mutableStateOf("") }
    var cookieHeader by remember { mutableStateOf("") }
    var hasSavedCookie by remember { mutableStateOf(false) }
    var savedHost by remember { mutableStateOf<String?>(null) }
    var sessionMessage by remember { mutableStateOf("") }

    LaunchedEffect(providerId) {
        val profile = runCatching { sessions.profile(providerId) }.getOrNull()
        hasSavedCookie = profile != null
        savedHost = profile?.host
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(stringResource(R.string.settings_title), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)

        Card(shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
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
                Text(stringResource(R.string.quality_explainer), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f))
                Text(stringResource(R.string.quality_format_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f))
            }
        }

        Card(shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Security, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.local_session_title), fontWeight = FontWeight.SemiBold)
                }
                Text(stringResource(R.string.local_session_explainer), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.68f))
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
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri)
                )
                OutlinedTextField(
                    value = cookieHeader,
                    onValueChange = { cookieHeader = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.cookie_value_label)) },
                    placeholder = { Text("session=value; token=value") },
                    minLines = 3,
                    maxLines = 5,
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
                        val result = runCatching { sessions.saveCookie(providerId, host, cookieHeader) }
                        if (result.isSuccess) {
                            cookieHeader = ""
                            hasSavedCookie = true
                            savedHost = runCatching { sessions.profile(providerId)?.host }.getOrNull()
                            sessionMessage = context.getString(R.string.local_session_save_success)
                        } else {
                            sessionMessage = result.exceptionOrNull()?.message ?: context.getString(R.string.local_session_save_error)
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

        Card(shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.settings_server), fontWeight = FontWeight.SemiBold)
                Text(BuildConfig.API_BASE_URL, color = MaterialTheme.colorScheme.primary)
                Text(stringResource(R.string.settings_server_value), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
            }
        }
        Card(shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.settings_version), fontWeight = FontWeight.SemiBold)
                Text(BuildConfig.VERSION_NAME + " (" + BuildConfig.VERSION_CODE + ")", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.68f))
                HorizontalDivider()
                Text(stringResource(R.string.status_unconnected), color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.58f), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
