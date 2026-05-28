package com.heartwith.shared

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.TextStyles
import top.yukonga.miuix.kmp.theme.ThemeController

@Composable
fun HeartwithTheme(
    fontFamily: FontFamily? = null,
    content: @Composable () -> Unit,
) {
    val controller = remember {
        ThemeController(ColorSchemeMode.System)
    }
    val textStyles = remember(fontFamily) {
        fontFamily?.let { heartwithTextStyles(it) }
    }
    if (textStyles == null) {
        MiuixTheme(controller = controller, content = content)
    } else {
        MiuixTheme(controller = controller, textStyles = textStyles, content = content)
    }
}

private fun heartwithTextStyles(fontFamily: FontFamily): TextStyles {
    val style = TextStyle(fontFamily = fontFamily)
    return TextStyles(
        main = style,
        paragraph = style,
        body1 = style,
        body2 = style,
        button = style,
        footnote1 = style,
        footnote2 = style,
        headline1 = style,
        headline2 = style,
        subtitle = style,
        title1 = style,
        title2 = style,
        title3 = style,
        title4 = style,
    )
}
