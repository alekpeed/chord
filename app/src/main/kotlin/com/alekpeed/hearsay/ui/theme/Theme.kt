package com.alekpeed.hearsay.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val InkIndigo = Color(0xFF4B45B8)
private val InkIndigoLight = Color(0xFF948CFF)
private val Spruce = Color(0xFF2E7D5B)
private val Brass = Color(0xFFB0761A)

private val LightColors = lightColorScheme(
    primary = InkIndigo,
    secondary = Spruce,
    tertiary = Brass,
)

private val DarkColors = darkColorScheme(
    primary = InkIndigoLight,
    secondary = Color(0xFF57BD8F),
    tertiary = Color(0xFFE0A33F),
)

/**
 * Chord symbols are read at arm's length from a music stand, so the display and headline sizes are
 * larger than Material's defaults and the weights heavier.
 */
private val HearsayTypography = Typography().let { base ->
    base.copy(
        displaySmall = base.displaySmall.copy(fontSize = 40.sp, fontWeight = FontWeight.SemiBold),
        headlineMedium = base.headlineMedium.copy(fontSize = 32.sp, fontWeight = FontWeight.Medium),
        titleMedium = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.Medium),
    )
}

@Composable
fun HearsayTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)

        darkTheme -> DarkColors
        else -> LightColors
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = HearsayTypography,
        content = content,
    )
}
