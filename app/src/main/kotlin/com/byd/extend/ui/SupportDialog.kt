package com.byd.extend.ui

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.byd.extend.R

private const val SUPPORT_JAR_URL = "https://send.monobank.ua/jar/bKFV15i9e"
private const val SUPPORT_JAR_CARD = "4874 1000 3354 3078"

@Composable
internal fun SupportDialog(strings: UiStrings, palette: UiPalette, onClose: () -> Unit) {
    val context = LocalContext.current
    val description = strings.resource(R.string.support_description)
    val cardLabel = strings.resource(R.string.support_card_label)
    val shareLabel = strings.resource(R.string.support_share)
    fun openIntent(intent: Intent) {
        try { context.startActivity(intent) }
        catch (_: ActivityNotFoundException) {
            Toast.makeText(context, strings.resource(R.string.support_action_unavailable), Toast.LENGTH_LONG).show()
        }
    }
    fun copy(value: String, label: String, message: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(label, value))
        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
    }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.width(820.dp).fillMaxHeight(0.9f).testTag("support-dialog")
            .clip(RoundedCornerShape(8.dp)).background(palette.surface)
            .border(1.dp, palette.borderStrong, RoundedCornerShape(8.dp)).padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(strings.resource(R.string.support_title), color = palette.text, fontSize = 22.sp,
                fontWeight = FontWeight.Bold)
            Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(22.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Image(painterResource(R.drawable.mono_support_qr), strings.resource(R.string.support_qr_description),
                    contentScale = ContentScale.Fit, modifier = Modifier.weight(0.42f).fillMaxHeight())
                Column(Modifier.weight(0.58f).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text("Support BYD app", color = palette.text, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                    Text(description, color = palette.text, fontSize = 16.sp)
                    Text(strings.resource(R.string.support_scan_hint), color = palette.muted, fontSize = 14.sp)
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(SUPPORT_JAR_URL, color = palette.accent, fontSize = 15.sp,
                            modifier = Modifier.weight(1f).testTag("support-link").clip(RoundedCornerShape(4.dp))
                                .clickable(role = Role.Button) { openIntent(Intent(Intent.ACTION_VIEW, Uri.parse(SUPPORT_JAR_URL))) }
                                .padding(vertical = 8.dp))
                        Text(strings.resource(R.string.support_copy), color = palette.accent, fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.testTag("support-copy-link").clip(RoundedCornerShape(4.dp))
                                .clickable(role = Role.Button, onClickLabel = strings.resource(R.string.support_copy_link)) {
                                    copy(SUPPORT_JAR_URL, "Support BYD app", strings.resource(R.string.support_link_copied))
                                }.padding(horizontal = 6.dp, vertical = 10.dp))
                    }
                    Column(Modifier.fillMaxWidth().testTag("support-copy-card").clip(RoundedCornerShape(7.dp))
                        .background(palette.field)
                        .border(1.dp, palette.border, RoundedCornerShape(7.dp))
                        .clickable(role = Role.Button, onClickLabel = strings.resource(R.string.support_copy_card)) {
                            copy(SUPPORT_JAR_CARD, cardLabel, strings.resource(R.string.support_card_copied))
                        }.padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text(cardLabel, color = palette.muted, fontSize = 13.sp)
                        Text(SUPPORT_JAR_CARD, color = palette.text, fontFamily = FontFamily.Monospace,
                            fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
                        Text(strings.resource(R.string.support_tap_to_copy), color = palette.accent, fontSize = 12.sp)
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                SupportDialogButton(shareLabel, palette, primary = true, icon = Icons.Outlined.Share,
                    modifier = Modifier.width(170.dp).testTag("support-share")) {
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_SUBJECT, "Support BYD app")
                        putExtra(Intent.EXTRA_TEXT, "Support BYD app\n\n$description\n\n$SUPPORT_JAR_URL\n\n$cardLabel: $SUPPORT_JAR_CARD")
                    }
                    openIntent(Intent.createChooser(intent, shareLabel))
                }
                Spacer(Modifier.width(10.dp))
                SupportDialogButton(strings.resource(R.string.support_close), palette, highlightOnPress = false,
                    modifier = Modifier.width(138.dp).testTag("support-close"), onClick = onClose)
            }
        }
    }
}

// HUD's support actions, scoped here so other Extend buttons keep their existing style.
@Composable
private fun SupportDialogButton(
    text: String,
    palette: UiPalette,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    icon: ImageVector? = null,
    highlightOnPress: Boolean = true,
    onClick: () -> Unit,
) {
    val press = rememberPressFeedback(releaseHoldMillis = 90L)
    val baseBackground = if (primary) palette.accent.copy(alpha = if (palette.dark) 0.78f else 0.08f)
        else palette.panelAlt
    val foreground = if (primary && palette.dark) Color.White else palette.text
    Box(modifier.height(44.dp).clip(RoundedCornerShape(7.dp))
        .border(1.dp, if (primary) palette.accent else palette.borderStrong, RoundedCornerShape(7.dp))
        .background(if (press.pressed && highlightOnPress) {
            palette.accent.copy(alpha = if (palette.dark) 0.78f else 0.20f)
        } else baseBackground)
        .then(press.modifier)
        .clickable(role = Role.Button, interactionSource = press.interactionSource,
            indication = null, onClick = onClick)
        .padding(horizontal = 12.dp), contentAlignment = Alignment.Center) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (icon != null) Icon(icon, null, modifier = Modifier.size(20.dp), tint = foreground)
            Text(text, color = foreground, fontWeight = FontWeight.SemiBold, fontSize = 14.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
