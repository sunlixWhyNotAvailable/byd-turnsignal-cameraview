package com.byd.extend.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.byd.extend.OptionalPacks
import com.byd.extend.R
import com.byd.extend.ui.icons.FileDownload
import com.byd.extend.ui.icons.Stop
import kotlinx.coroutines.delay

@Composable
internal fun rememberPackStates(keys: List<String>): Map<String, OptionalPacks.State> {
    val context = LocalContext.current
    val packs = remember(context) { OptionalPacks.get(context) }
    var states by remember(packs, keys) { mutableStateOf(keys.associateWith { packs.state(it) }) }
    DisposableEffect(packs, keys) {
        val listener = Runnable { states = keys.associateWith { packs.state(it) } }
        packs.addListener(listener)
        listener.run()
        packs.refresh()
        onDispose { packs.removeListener(listener) }
    }
    val busy = states.values.any { it.busy() }
    LaunchedEffect(packs, busy) {
        while (busy) { delay(250); packs.refresh() }
    }
    return states
}

@Composable
internal fun PackDownloadButton(key: String, label: String, state: OptionalPacks.State,
    colors: UiPalette, strings: UiStrings, modifier: Modifier = Modifier) {
    if (state.ready()) return
    val packs = OptionalPacks.get(LocalContext.current)
    val busy = state.busy()
    val status = when {
        state.phase == "installing" -> strings.resource(R.string.asset_download_installing)
        busy -> strings.resource(R.string.asset_download_progress).format((state.progress * 100).toInt())
        state.phase == "failed" -> strings.resource(R.string.asset_download_failed)
        else -> ""
    }
    IconButton(onClick = { if (busy) packs.cancel(key) else packs.download(key) },
        modifier = modifier.size(48.dp).semantics { stateDescription = status }) {
        Box(contentAlignment = Alignment.Center) {
            if (state.phase == "installing") CircularProgressIndicator(modifier = Modifier.size(34.dp),
                color = colors.accent, trackColor = colors.borderStrong, strokeWidth = 2.dp)
            else if (busy) CircularProgressIndicator(progress = { state.progress }, modifier = Modifier.size(34.dp),
                color = colors.accent, trackColor = colors.borderStrong, strokeWidth = 2.dp)
            Icon(if (busy) Icons.Outlined.Stop else Icons.Outlined.FileDownload,
                contentDescription = strings.resource(if (busy) R.string.asset_download_stop else R.string.asset_download).format(label),
                tint = if (state.phase == "failed") colors.red else colors.accent, modifier = Modifier.size(22.dp))
        }
    }
}
