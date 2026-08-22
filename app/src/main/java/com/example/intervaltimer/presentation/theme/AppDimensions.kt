package com.example.intervaltimer.presentation.theme

import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Compose-native mirror of res/values/dimensions.xml (Task 5). See that
 * file's header comment for why the Sp values can't just be read via
 * dimensionResource() -- that API always returns Dp, never TextUnit, even
 * for a dimension authored as "sp" in XML. Keep both files in sync.
 */
object AppDimensions {
    val ButtonHeight = 56.dp
    val ButtonWidth = 100.dp
    val ButtonCornerRadius = 8.dp
    val PaddingLarge = 24.dp
    val PaddingStandard = 16.dp

    val TextSizeButton = 28.sp
    val TextSizeSecondary = 32.sp
}
