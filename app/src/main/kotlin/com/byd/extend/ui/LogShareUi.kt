package com.byd.extend.ui

import android.content.ClipData
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.findViewTreeLifecycleOwner
import com.byd.extend.LogShareRuntime
import com.byd.extend.R
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
internal fun LogShareUi(colors: UiPalette, strings: UiStrings) {
    val context = LocalContext.current
    val runtime = remember(context.applicationContext) { LogShareRuntime.get(context.applicationContext) }
    var revision by remember { mutableIntStateOf(0) }
    var now by remember { mutableLongStateOf(android.os.SystemClock.elapsedRealtime()) }
    val lifecycle = LocalView.current.findViewTreeLifecycleOwner()?.lifecycle
    var resumed by remember { mutableStateOf(lifecycle?.currentState?.isAtLeast(Lifecycle.State.RESUMED) == true) }
    DisposableEffect(runtime, lifecycle) {
        val listener = Runnable { revision++ }
        val observer = LifecycleEventObserver { _, _ -> resumed = lifecycle?.currentState?.isAtLeast(Lifecycle.State.RESUMED) == true }
        runtime.observe(listener); lifecycle?.addObserver(observer)
        onDispose { runtime.removeObserver(listener); lifecycle?.removeObserver(observer) }
    }
    val jobs = remember(revision) { runtime.snapshots() }
    val open = remember(revision) { runtime.isDialogOpen }
    var commentOpen by rememberSaveable { mutableStateOf(false) }
    var comment by rememberSaveable { mutableStateOf("") }
    var detailsId by rememberSaveable { mutableStateOf<String?>(null) }
    var saveId by rememberSaveable { mutableStateOf<String?>(null) }
    var sessionReady by remember { mutableStateOf(false) }
    val loaded = remember(revision) { runtime.isLoaded }
    LaunchedEffect(runtime, loaded) {
        if (loaded) {
            withContext(Dispatchers.IO) { runtime.resumeUi(saveId) }
            sessionReady = true
        }
    }
    val modalVisible = open || detailsId != null || jobs.any { it.phase == "oversized" && !it.shareRequested }
    DisposableEffect(modalVisible) {
        runtime.setModalVisible(modalVisible)
        onDispose { runtime.setModalVisible(false) }
    }
    val document = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        saveId?.let { runtime.save(it, uri) }; saveId = null
    }
    LaunchedEffect(jobs.any { it.active } || runtime.cooldownSeconds() > 0) {
        while (runtime.snapshots().any { it.active } || runtime.cooldownSeconds() > 0) {
            delay(1000); now = android.os.SystemClock.elapsedRealtime()
        }
    }
    val cooldown = now.let { runtime.cooldownSeconds() }
    val ready = jobs.firstOrNull { sessionReady && it.id != saveId && !it.handled && runtime.archiveReady(it.id) &&
        (it.phase == "ready" || (it.phase == "oversized" && it.shareRequested)) }
    LaunchedEffect(ready?.id, resumed) {
        if (ready != null && resumed) {
            try {
                val archive = runtime.archive(ready.id)
                runtime.handled(ready.id)
                if (ready.destination == "save") {
                    saveId = ready.id; document.launch(archive.name)
                } else {
                    val uri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", archive)
                    val intent = Intent(Intent.ACTION_SEND).setType("application/zip").putExtra(Intent.EXTRA_STREAM, uri)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    intent.clipData = ClipData.newUri(context.contentResolver, "BYD Extend logs", uri)
                    context.startActivity(Intent.createChooser(intent, strings.resource(R.string.log_share_title)))
                    runtime.finish(ready.id, "shared", null)
                }
            } catch (error: Exception) { runtime.finish(ready.id, "failed", error) }
        }
    }
    fun titleFallback() = strings.resource(R.string.log_share_title) + " — " +
        SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale.getDefault()).format(Date())
    fun summary(job: LogShareRuntime.Job): String {
        val seconds = ((if (job.active) now else job.endedAt.takeIf { it > 0 } ?: now) - job.startedAt).coerceAtLeast(0) / 1000
        return strings.resource(R.string.log_share_card_summary, String.format(strings.locale, "%.2f", job.bytes / 1_000_000.0), seconds)
    }
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.align(Alignment.BottomEnd).padding(end = 18.dp, bottom = 92.dp)
            .heightIn(max = 370.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            jobs.filterNot { it.hidden }.forEach { job -> key(job.id) {
                Column(Modifier.width(460.dp).height(170.dp).clip(RoundedCornerShape(8.dp))
                    .background(colors.surface.copy(alpha = .90f)).border(1.dp, colors.borderStrong, RoundedCornerShape(8.dp)).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(strings.resource(R.string.log_share_upload_title) + " · " + SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(job.createdAt)),
                        color = colors.text, fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (job.active) CircularProgressIndicator(Modifier.size(26.dp), color = colors.accent)
                        Column(Modifier.weight(1f)) {
                            Text(strings.resource(phaseLabel(job.phase)), color = if (job.phase == "success") colors.green else colors.text,
                                fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(summary(job), color = colors.muted, fontSize = 12.sp, maxLines = 2)
                        }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End)) {
                        ActionButton(strings.resource(R.string.log_share_details), colors, Modifier.width(105.dp)) { detailsId = job.id }
                        if (job.preparing) ActionButton(strings.resource(R.string.log_share_stop), colors, Modifier.width(105.dp)) { runtime.cancel(job.id) }
                        else if (job.phase != "oversized") ActionButton(strings.resource(R.string.update_dialog_close), colors, Modifier.width(105.dp)) { runtime.hide(job.id) }
                    }
                }
            } }
        }
    }
    if (open && !commentOpen) ShareModal(if (runtime.isSaveOnly) 560.dp else 880.dp, colors, runtime::closeDialog) {
        Heading(strings.resource(R.string.log_share_title), colors)
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(colors.field)
            .border(1.dp, colors.border, RoundedCornerShape(8.dp)).padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(strings.resource(R.string.log_share_selection), color = colors.yellow, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            listOf(R.string.log_share_archive_hint, R.string.log_share_sensitive, R.string.log_share_sentry_notice,
                R.string.log_share_no_adb, R.string.log_share_save_hint).forEach { resource ->
                Text(strings.resource(resource), color = if (resource == R.string.log_share_sensitive) colors.yellow else colors.text, fontSize = 15.sp, lineHeight = 21.sp)
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End)) {
            if (!runtime.isSaveOnly) {
                ActionButton(if (cooldown > 0) strings.resource(R.string.log_share_cooldown, cooldown) else strings.resource(R.string.log_share_developer),
                    colors, Modifier.width(210.dp), primary = true, enabled = runtime.canSend()) { commentOpen = true }
                ActionButton(strings.resource(R.string.log_share_other_app), colors, Modifier.width(190.dp), enabled = runtime.canPrepare()) {
                    runtime.begin("share", "", titleFallback())
                }
            }
            ActionButton(strings.resource(R.string.log_share_save_as), colors, Modifier.width(180.dp), mainBackground = true, enabled = runtime.canPrepare()) {
                runtime.begin("save", "", titleFallback())
            }
            ActionButton(strings.resource(R.string.runtime_cancel), colors, Modifier.width(140.dp)) { runtime.closeDialog() }
        }
    }
    if (open && commentOpen) ShareModal(560.dp, colors, { commentOpen = false }) {
        Heading(strings.resource(R.string.log_share_comment_title), colors)
        BasicTextField(comment, { comment = it }, minLines = 3, maxLines = 5,
            textStyle = TextStyle(color = colors.text, fontSize = 16.sp), cursorBrush = SolidColor(colors.accent),
            modifier = Modifier.fillMaxWidth().heightIn(min = 110.dp).clip(RoundedCornerShape(7.dp))
                .background(colors.field).border(1.dp, colors.borderStrong, RoundedCornerShape(7.dp)).padding(10.dp)
                .semantics { contentDescription = strings.resource(R.string.log_share_comment_title) },
            decorationBox = { field -> Box {
                if (comment.isEmpty()) Text(strings.resource(R.string.log_share_comment_hint), color = colors.muted, fontSize = 16.sp)
                field()
            } })
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            ActionButton(strings.resource(R.string.log_share_ok), colors, Modifier.width(138.dp), primary = true, enabled = runtime.canSend()) {
                runtime.begin("developer", comment.trim(), titleFallback()); commentOpen = false; comment = ""
            }
        }
    }
    jobs.firstOrNull { it.phase == "oversized" && !it.shareRequested }?.let { job ->
        if (!open && detailsId == null) ShareModal(560.dp, colors, { runtime.finish(job.id, "cancelled", null) }) {
            Heading(strings.resource(R.string.log_share_limit_title), colors, true)
            Text(strings.resource(R.string.log_share_limit_body, job.bytes / 1_000_000), color = colors.text, fontSize = 16.sp)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End)) {
                ActionButton(strings.resource(R.string.log_share_other_app), colors, Modifier.width(220.dp), primary = true,
                    enabled = runtime.archiveReady(job.id)) { runtime.anotherApp(job.id) }
                ActionButton(strings.resource(R.string.runtime_cancel), colors, Modifier.width(140.dp)) { runtime.finish(job.id, "cancelled", null) }
            }
        }
    }
    jobs.firstOrNull { it.id == detailsId }?.let { job ->
        ShareModal(560.dp, colors, { detailsId = null }) {
            Heading(strings.resource(R.string.log_share_title), colors)
            Text(strings.resource(phaseLabel(job.phase)), color = colors.text, fontSize = 16.sp)
            Text(job.title, color = colors.text, fontSize = 15.sp)
            if (job.comment.isNotEmpty()) Text(job.comment, color = colors.text, fontSize = 15.sp)
            Text(summary(job), color = colors.muted, fontSize = 14.sp)
            if (!job.systemLogs) Text(strings.resource(R.string.log_share_no_adb), color = colors.yellow, fontSize = 14.sp)
            if (job.eventId.isNotEmpty()) Text("Event ID: ${job.eventId}", color = colors.muted, fontSize = 13.sp)
            if (job.detail.isNotEmpty()) Text(job.detail, color = colors.muted, fontSize = 13.sp)
            if (job.path.isNotEmpty()) Text(job.path, color = colors.muted, fontSize = 13.sp)
            Text(strings.resource(R.string.log_share_close_hint), color = colors.muted, fontSize = 13.sp)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                ActionButton(strings.resource(R.string.update_dialog_close), colors, Modifier.width(138.dp)) { detailsId = null }
            }
        }
    }
}

private fun phaseLabel(phase: String) = when (phase) {
    "waiting" -> R.string.log_share_waiting
    "copying" -> R.string.log_share_copying
    "archiving" -> R.string.log_share_archiving
    "uploading" -> R.string.log_share_uploading
    "success" -> R.string.log_share_success
    "oversized" -> R.string.log_share_oversized
    "cancelled" -> R.string.log_share_cancelled
    "shared" -> R.string.log_share_shared
    "saved" -> R.string.log_share_saved
    "ready" -> R.string.log_share_ready
    "unconfirmed" -> R.string.log_share_unconfirmed
    else -> R.string.log_share_failed
}

@Composable
private fun Heading(text: String, colors: UiPalette, error: Boolean = false) {
    Text(text, color = if (error) colors.red else colors.text, fontSize = 22.sp, fontWeight = FontWeight.Bold)
}

@Composable
private fun ShareModal(width: Dp, colors: UiPalette, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false)) {
        val window = (LocalView.current.parent as? DialogWindowProvider)?.window
        SideEffect { window?.setDimAmount(if (colors.dark) .48f else .32f) }
        Column(Modifier.widthIn(max = width).fillMaxWidth().imePadding()
            .heightIn(max = (LocalConfiguration.current.screenHeightDp - 36).dp)
            .clip(RoundedCornerShape(8.dp)).background(colors.surface)
            .border(1.dp, colors.borderStrong, RoundedCornerShape(8.dp))
            .verticalScroll(rememberScrollState()).padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp), content = content)
    }
}
