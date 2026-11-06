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
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.materialIcon
import androidx.compose.material.icons.materialPath
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
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
private const val SUPPORT_PAYPAL_EMAIL = "opriva1@gmail.com"
private const val PROJECT_REPOSITORY_URL = "https://github.com/sunlixWhyNotAvailable/byd-turnsignal-cameraview"

@Composable
internal fun SupportDialog(strings: UiStrings, palette: UiPalette, onClose: () -> Unit) {
    val context = LocalContext.current
    var paypalSelected by rememberSaveable { mutableStateOf(false) }
    val description = strings.resource(R.string.support_description)
    val cardLabel = strings.resource(R.string.support_card_label)
    val emailLabel = strings.resource(R.string.support_paypal_email_label)
    val shareLabel = strings.resource(R.string.support_share)
    fun openIntent(intent: Intent) {
        try { context.startActivity(intent) }
        catch (_: ActivityNotFoundException) {
            Toast.makeText(context, strings.resource(R.string.support_action_unavailable), Toast.LENGTH_SHORT).show()
        }
    }
    fun copy(value: String, label: String, message: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(label, value))
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.width(820.dp).fillMaxHeight(0.9f).testTag("support-dialog")
            .clip(RoundedCornerShape(8.dp)).background(palette.surface)
            .border(1.dp, palette.borderStrong, RoundedCornerShape(8.dp)).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(strings.resource(R.string.support_title), modifier = Modifier.weight(1f),
                    color = palette.text, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Row(Modifier.width(280.dp).selectableGroup().clip(RoundedCornerShape(7.dp))
                    .background(palette.panelAlt).border(1.dp, palette.borderStrong, RoundedCornerShape(7.dp))
                    .padding(2.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    listOf("monobank", "PayPal").forEachIndexed { index, label ->
                        val selected = paypalSelected == (index == 1)
                        val press = rememberPressFeedback()
                        Box(Modifier.weight(1f).height(36.dp).clip(RoundedCornerShape(5.dp))
                            .background(pressBackground(if (selected) palette.active else Color.Transparent, palette, press.pressed))
                            .then(press.modifier)
                            .selectable(selected = selected, role = Role.Tab,
                                interactionSource = press.interactionSource, indication = null,
                                onClick = { paypalSelected = index == 1 }), contentAlignment = Alignment.Center) {
                            Text(label, color = if (selected) palette.text else palette.muted,
                                fontSize = 16.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
                        }
                    }
                }
            }
            Text(description, color = palette.text, fontSize = 15.sp)
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(Modifier.clip(RoundedCornerShape(4.dp))
                    .clickable(role = Role.Button) { openIntent(Intent(Intent.ACTION_VIEW, Uri.parse(PROJECT_REPOSITORY_URL))) }
                    .padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(OpenInNewIcon, contentDescription = null, tint = palette.accent, modifier = Modifier.size(18.dp))
                    Text(strings.resource(R.string.support_repository),
                        color = palette.accent, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                }
                Row(Modifier.clip(RoundedCornerShape(7.dp)).background(palette.field)
                        .border(1.dp, palette.border, RoundedCornerShape(7.dp))
                        .clickable(role = Role.Button, onClickLabel = strings.resource(R.string.support_copy_repository)) {
                            copy(PROJECT_REPOSITORY_URL, "BYD Extend", strings.resource(R.string.support_link_copied))
                        }.padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val linkColor = if (palette.dark) palette.yellow else Color(0xFF8A6500)
                    Icon(ContentCopyIcon, contentDescription = null, tint = linkColor, modifier = Modifier.size(18.dp))
                    Text(PROJECT_REPOSITORY_URL, color = linkColor, fontSize = 14.sp)
                }
            }
            if (paypalSelected) {
                Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically)) {
                    Text(strings.resource(R.string.support_paypal_hint), color = palette.muted, fontSize = 16.sp)
                    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(7.dp)).background(palette.field)
                        .border(1.dp, palette.border, RoundedCornerShape(7.dp))
                        .clickable(role = Role.Button, onClickLabel = strings.resource(R.string.support_copy_email)) {
                            copy(SUPPORT_PAYPAL_EMAIL, emailLabel, strings.resource(R.string.support_email_copied))
                        }.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(emailLabel, color = palette.muted, fontSize = 14.sp)
                        Text(SUPPORT_PAYPAL_EMAIL, color = palette.text, fontFamily = FontFamily.Monospace,
                            fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
                        Text(strings.resource(R.string.support_tap_to_copy), color = palette.accent, fontSize = 14.sp)
                    }
                }
            } else Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(22.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Image(painterResource(R.drawable.mono_support_qr), strings.resource(R.string.support_qr_description),
                    contentScale = ContentScale.Fit, modifier = Modifier.weight(0.42f).fillMaxHeight())
                Column(Modifier.weight(0.58f).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text("Support BYD app", color = palette.text, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
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
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
                SupportDialogButton(shareLabel, palette, primary = true, icon = Icons.Outlined.Share,
                    modifier = Modifier.width(170.dp).testTag("support-share")) {
                    val paymentDetails = if (paypalSelected) "$emailLabel: $SUPPORT_PAYPAL_EMAIL"
                        else "$SUPPORT_JAR_URL\n\n$cardLabel: $SUPPORT_JAR_CARD"
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_SUBJECT, "Support BYD app")
                        putExtra(Intent.EXTRA_TEXT, "BYD Extend\n\n$description\n\n$PROJECT_REPOSITORY_URL\n\n$paymentDetails")
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

// ponytail: path data copied from androidx material-icons-extended 1.7.4 (Apache-2.0) instead of
// adding that ~36 MB library to an APK built without shrinking.
private val ContentCopyIcon: ImageVector = materialIcon(name = "Outlined.ContentCopy") {
    materialPath {
        moveTo(16.0f, 1.0f)
        lineTo(4.0f, 1.0f)
        curveToRelative(-1.1f, 0.0f, -2.0f, 0.9f, -2.0f, 2.0f)
        verticalLineToRelative(14.0f)
        horizontalLineToRelative(2.0f)
        lineTo(4.0f, 3.0f)
        horizontalLineToRelative(12.0f)
        lineTo(16.0f, 1.0f)
        close()
        moveTo(19.0f, 5.0f)
        lineTo(8.0f, 5.0f)
        curveToRelative(-1.1f, 0.0f, -2.0f, 0.9f, -2.0f, 2.0f)
        verticalLineToRelative(14.0f)
        curveToRelative(0.0f, 1.1f, 0.9f, 2.0f, 2.0f, 2.0f)
        horizontalLineToRelative(11.0f)
        curveToRelative(1.1f, 0.0f, 2.0f, -0.9f, 2.0f, -2.0f)
        lineTo(21.0f, 7.0f)
        curveToRelative(0.0f, -1.1f, -0.9f, -2.0f, -2.0f, -2.0f)
        close()
        moveTo(19.0f, 21.0f)
        lineTo(8.0f, 21.0f)
        lineTo(8.0f, 7.0f)
        horizontalLineToRelative(11.0f)
        verticalLineToRelative(14.0f)
        close()
    }
}

private val OpenInNewIcon: ImageVector = materialIcon(name = "AutoMirrored.Outlined.OpenInNew", autoMirror = true) {
    materialPath {
        moveTo(19.0f, 19.0f)
        horizontalLineTo(5.0f)
        verticalLineTo(5.0f)
        horizontalLineToRelative(7.0f)
        verticalLineTo(3.0f)
        horizontalLineTo(5.0f)
        curveToRelative(-1.11f, 0.0f, -2.0f, 0.9f, -2.0f, 2.0f)
        verticalLineToRelative(14.0f)
        curveToRelative(0.0f, 1.1f, 0.89f, 2.0f, 2.0f, 2.0f)
        horizontalLineToRelative(14.0f)
        curveToRelative(1.1f, 0.0f, 2.0f, -0.9f, 2.0f, -2.0f)
        verticalLineToRelative(-7.0f)
        horizontalLineToRelative(-2.0f)
        verticalLineToRelative(7.0f)
        close()
        moveTo(14.0f, 3.0f)
        verticalLineToRelative(2.0f)
        horizontalLineToRelative(3.59f)
        lineToRelative(-9.83f, 9.83f)
        lineToRelative(1.41f, 1.41f)
        lineTo(19.0f, 6.41f)
        verticalLineTo(10.0f)
        horizontalLineToRelative(2.0f)
        verticalLineTo(3.0f)
        horizontalLineToRelative(-7.0f)
        close()
    }
}
