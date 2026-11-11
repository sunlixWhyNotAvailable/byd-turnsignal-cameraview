package com.byd.extend.ui.icons

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.materialIcon
import androidx.compose.material.icons.materialPath
import androidx.compose.ui.graphics.vector.ImageVector

private val download = materialIcon(name = "Outlined.FileDownload") {
    materialPath {
        moveTo(11f, 3f); lineTo(13f, 3f); lineTo(13f, 12f)
        lineTo(16f, 9f); lineTo(17.4f, 10.4f); lineTo(12f, 15.8f)
        lineTo(6.6f, 10.4f); lineTo(8f, 9f); lineTo(11f, 12f); close()
        moveTo(5f, 19f); lineTo(19f, 19f); lineTo(19f, 21f); lineTo(5f, 21f); close()
    }
}
internal val Icons.Outlined.FileDownload: ImageVector get() = download
