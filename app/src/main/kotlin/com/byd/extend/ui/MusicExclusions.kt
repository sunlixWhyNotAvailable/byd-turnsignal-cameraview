package com.byd.extend.ui

import com.byd.extend.R
import com.byd.extend.remote.CameraViewingDiagnostics
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.graphics.drawable.toBitmap
import java.text.Collator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
private data class VisualizationApp(val packageName: String, val label: String, val icon: ImageBitmap)

@Composable
internal fun MusicExclusionsRow(
    selected: Set<String>, palette: UiPalette, strings: UiStrings, onSave: (Set<String>) -> Unit,
) {
    var choosing by rememberSaveable { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        ActionButton(strings.resource(R.string.music_exclude_visualization), colors = palette,
            modifier = Modifier.weight(1f), maxLines = 2) { choosing = true }
        Text(strings.resource(R.string.music_excluded_count, selected.size), color = palette.muted,
            fontSize = 14.sp)
    }
    if (choosing) MusicExclusionsDialog(selected, palette, strings,
        onClose = { choosing = false }, onSave = { next ->
            onSave(next)
            choosing = false
        })
}
@Composable
private fun MusicExclusionsDialog(
    initial: Set<String>, palette: UiPalette, strings: UiStrings,
    onClose: () -> Unit, onSave: (Set<String>) -> Unit,
) {
    val context = LocalContext.current
    var draft by rememberSaveable { mutableStateOf(initial.toList()) }
    var query by rememberSaveable { mutableStateOf("") }
    var apps by remember { mutableStateOf<List<VisualizationApp>?>(null) }
    var failed by remember { mutableStateOf(false) }
    LaunchedEffect(context, strings) {
        apps = null
        failed = false
        try {
            apps = withContext(Dispatchers.IO) {
                val manager = context.packageManager
                val collator = Collator.getInstance(context.resources.configuration.locales[0])
                @Suppress("DEPRECATION")
                val installed = manager.getInstalledApplications(0)
                installed.filter { manager.getLaunchIntentForPackage(it.packageName) != null }.map { app ->
                    VisualizationApp(app.packageName, app.loadLabel(manager).toString(),
                        app.loadIcon(manager).toBitmap(96, 96).asImageBitmap())
                }.sortedWith { first, second ->
                    collator.compare(first.label, second.label)
                        .takeIf { it != 0 } ?: first.packageName.compareTo(second.packageName)
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            CameraViewingDiagnostics.failure("installed_apps", "music_exclusions", error)
            failed = true
        }
    }
    val visible = remember(apps, query) {
        apps.orEmpty().filter { it.label.contains(query.trim(), ignoreCase = true) }
    }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.widthIn(max = 720.dp).fillMaxWidth(.94f).fillMaxHeight(.88f)
            .clip(RoundedCornerShape(8.dp)).background(palette.surface)
            .border(1.dp, palette.borderStrong, RoundedCornerShape(8.dp)).padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(strings.resource(R.string.music_exclude_visualization), color = palette.text,
                    fontSize = 22.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Text(strings.resource(R.string.music_excluded_count, draft.size), color = palette.muted,
                    fontSize = 14.sp)
                ActionButton(strings.resource(R.string.music_clear_exclusions), colors = palette,
                    modifier = Modifier.width(130.dp), height = 36.dp,
                    enabled = draft.isNotEmpty()) { draft = emptyList() }
            }
            BasicTextField(query, { query = it }, singleLine = true,
                textStyle = TextStyle(color = palette.text, fontSize = 16.sp),
                cursorBrush = SolidColor(palette.accent),
                modifier = Modifier.fillMaxWidth().height(44.dp)
                    .semantics { contentDescription = strings.resource(R.string.music_apps_search) }
                    .clip(RoundedCornerShape(7.dp)).background(palette.field)
                    .border(1.dp, palette.borderStrong, RoundedCornerShape(7.dp)),
                decorationBox = { input ->
                    Row(Modifier.fillMaxSize().padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Outlined.Search, null, tint = palette.muted, modifier = Modifier.size(20.dp))
                        Box(Modifier.weight(1f)) {
                            if (query.isEmpty()) Text(strings.resource(R.string.music_apps_search),
                                color = palette.muted, fontSize = 16.sp)
                            input()
                        }
                    }
                })
            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (failed || apps == null || visible.isEmpty()) {
                    Text(strings.resource(when {
                        failed -> R.string.music_apps_error
                        apps == null -> R.string.music_apps_loading
                        else -> R.string.music_apps_empty
                    }), color = if (failed) palette.red else palette.muted,
                        modifier = Modifier.align(Alignment.Center))
                } else LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(visible, key = { it.packageName }) { app ->
                        val checked = app.packageName in draft
                        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(7.dp))
                            .background(if (checked) palette.active else palette.panel)
                            .toggleable(checked, role = Role.Checkbox, onValueChange = { selected ->
                                draft = if (selected) draft + app.packageName else draft - app.packageName
                            }).padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Image(app.icon, null, Modifier.size(36.dp))
                            Text(app.label, color = palette.text, fontSize = 16.sp,
                                modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Checkbox(checked, onCheckedChange = null,
                                colors = CheckboxDefaults.colors(checkedColor = palette.accent,
                                    uncheckedColor = palette.muted, checkmarkColor = palette.surface))
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ActionButton(strings.resource(R.string.calls_save), colors = palette, primary = true,
                    modifier = Modifier.weight(1f), enabled = apps != null && !failed) { onSave(draft.toSet()) }
                ActionButton(strings.resource(R.string.cancel), colors = palette,
                    modifier = Modifier.weight(1f), onClick = onClose)
            }
        }
    }
}
