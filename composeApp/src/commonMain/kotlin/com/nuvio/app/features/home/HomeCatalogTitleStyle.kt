package com.nuvio.app.features.home

import kotlinx.serialization.Serializable

const val HOME_CATALOG_TITLE_MIN_SIZE_SP = 16
const val HOME_CATALOG_TITLE_MAX_SIZE_SP = 32
const val HOME_CATALOG_TITLE_DEFAULT_SIZE_SP = 22

/**
 * A lightweight, cross-platform style for one homepage catalog heading.
 *
 * Font files themselves remain local to the device. Only the selected font ID is synchronized;
 * devices without that font fall back to the platform's default subtitle font.
 */
@Serializable
data class HomeCatalogTitleStyle(
    val fontId: String = "default",
    val fontSizeSp: Int = HOME_CATALOG_TITLE_DEFAULT_SIZE_SP,
    val bold: Boolean = false,
    val colorId: String? = null,
) {
    fun normalized(): HomeCatalogTitleStyle = copy(
        fontId = fontId.takeIf {
            it in BUILT_IN_FONT_IDS || it.matches(Regex("custom-[a-f0-9-]{36}"))
        } ?: "default",
        fontSizeSp = fontSizeSp.coerceIn(HOME_CATALOG_TITLE_MIN_SIZE_SP, HOME_CATALOG_TITLE_MAX_SIZE_SP),
        colorId = colorId?.takeIf { it in HOME_CATALOG_TITLE_COLOR_IDS },
    )

    val isDefault: Boolean
        get() = normalized() == DEFAULT

    companion object {
        val DEFAULT = HomeCatalogTitleStyle()
    }
}

internal val BUILT_IN_FONT_IDS = setOf("default", "sans", "serif", "mono")

internal val HOME_CATALOG_TITLE_COLOR_IDS = listOf(
    "red", "orange", "yellow", "green", "cyan", "blue", "purple", "pink",
)

/** Accessible text colors selected for the current surface luminance. */
internal fun homeCatalogTitleColorArgb(colorId: String?, darkBackground: Boolean): Int? =
    when (colorId) {
        "red" -> if (darkBackground) 0xFFFF8A80.toInt() else 0xFFB3261E.toInt()
        "orange" -> if (darkBackground) 0xFFFFB74D.toInt() else 0xFFA04D00.toInt()
        "yellow" -> if (darkBackground) 0xFFFFE082.toInt() else 0xFF7A5B00.toInt()
        "green" -> if (darkBackground) 0xFF72D6A1.toInt() else 0xFF146C43.toInt()
        "cyan" -> if (darkBackground) 0xFF67E5F2.toInt() else 0xFF006B78.toInt()
        "blue" -> if (darkBackground) 0xFF8AB4FF.toInt() else 0xFF2454A6.toInt()
        "purple" -> if (darkBackground) 0xFFD0A3FF.toInt() else 0xFF713B99.toInt()
        "pink" -> if (darkBackground) 0xFFFFA6CC.toInt() else 0xFF9C245F.toInt()
        else -> null
    }
