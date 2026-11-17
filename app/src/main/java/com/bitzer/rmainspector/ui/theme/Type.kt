package com.bitzer.rmainspector.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.bitzer.rmainspector.R

// Style guide: Source Sans for software applications (running text). AF Diwa (headlines) is licensed and
// not bundled; headlines use Source Sans Bold in capitals instead. Font licence: third_party/fonts.
val SourceSans = FontFamily(
    Font(R.font.source_sans_regular, FontWeight.Normal),
    Font(R.font.source_sans_semibold, FontWeight.SemiBold),
    Font(R.font.source_sans_bold, FontWeight.Bold),
)

private val base = Typography()

// Sizes a step above phone defaults: tablets on a shop bench, read at arm's length.
val RmaTypography = Typography(
    displaySmall = base.displaySmall.copy(fontFamily = SourceSans, fontWeight = FontWeight.Bold),
    headlineMedium = base.headlineMedium.copy(fontFamily = SourceSans, fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp),
    headlineSmall = base.headlineSmall.copy(fontFamily = SourceSans, fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp),
    titleLarge = base.titleLarge.copy(fontFamily = SourceSans, fontWeight = FontWeight.Bold, fontSize = 24.sp),
    titleMedium = base.titleMedium.copy(fontFamily = SourceSans, fontWeight = FontWeight.SemiBold, fontSize = 20.sp),
    titleSmall = base.titleSmall.copy(fontFamily = SourceSans, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontFamily = SourceSans, fontSize = 18.sp, lineHeight = 26.sp),
    bodyMedium = TextStyle(fontFamily = SourceSans, fontSize = 16.sp, lineHeight = 22.sp),
    bodySmall = TextStyle(fontFamily = SourceSans, fontSize = 14.sp, lineHeight = 20.sp),
    labelLarge = base.labelLarge.copy(fontFamily = SourceSans, fontWeight = FontWeight.SemiBold, fontSize = 16.sp),
    labelMedium = base.labelMedium.copy(fontFamily = SourceSans, fontWeight = FontWeight.SemiBold),
    labelSmall = base.labelSmall.copy(fontFamily = SourceSans),
)
