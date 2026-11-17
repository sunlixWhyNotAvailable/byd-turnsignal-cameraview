/*
 * Copyright 2024 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.byd.extend.ui.icons

import androidx.compose.material.icons.Icons
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

// Material Tune, kept locally like the other icons to avoid the extended icon dependency.
val Icons.Outlined.Tune: ImageVector
    get() = tune ?: ImageVector.Builder("Tune", 24.dp, 24.dp, 24f, 24f).apply {
        path(fill = SolidColor(Color.Black)) {
            moveTo(3f,17f); verticalLineTo(19f); horizontalLineTo(9f); verticalLineTo(17f); close()
            moveTo(3f,5f); verticalLineTo(7f); horizontalLineTo(13f); verticalLineTo(5f); close()
            moveTo(13f,21f); verticalLineTo(19f); horizontalLineTo(21f); verticalLineTo(17f)
            horizontalLineTo(13f); verticalLineTo(15f); horizontalLineTo(11f); verticalLineTo(21f); close()
            moveTo(7f,9f); verticalLineTo(11f); horizontalLineTo(3f); verticalLineTo(13f)
            horizontalLineTo(7f); verticalLineTo(15f); horizontalLineTo(9f); verticalLineTo(9f); close()
            moveTo(21f,13f); verticalLineTo(11f); horizontalLineTo(11f); verticalLineTo(13f); close()
            moveTo(15f,9f); horizontalLineTo(17f); verticalLineTo(7f); horizontalLineTo(21f)
            verticalLineTo(5f); horizontalLineTo(17f); verticalLineTo(3f); horizontalLineTo(15f); close()
        }
    }.build().also { tune = it }

private var tune: ImageVector? = null
