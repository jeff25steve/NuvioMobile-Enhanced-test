package com.nuvio.app.features.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.home.HOME_CATALOG_TITLE_COLOR_IDS
import com.nuvio.app.features.home.HOME_CATALOG_TITLE_MAX_SIZE_SP
import com.nuvio.app.features.home.HOME_CATALOG_TITLE_MIN_SIZE_SP
import com.nuvio.app.features.home.HomeCatalogTitleStyle
import com.nuvio.app.features.home.homeCatalogTitleColorArgb
import com.nuvio.app.features.player.SubtitleFontPicker
import com.nuvio.app.features.player.rememberSubtitleFontFamily
import com.nuvio.app.features.player.subtitleFontLabel
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.stringResource
import kotlin.math.roundToInt

@Composable
internal fun CatalogTitleAppearanceEditor(
    title: String,
    style: HomeCatalogTitleStyle,
    isTablet: Boolean,
    onStyleChanged: (HomeCatalogTitleStyle) -> Unit,
) {
    var showFonts by remember { mutableStateOf(false) }
    val normalized = style.normalized()
    var pendingFontSize by remember(normalized.fontSizeSp) {
        mutableStateOf(normalized.fontSizeSp.toFloat())
    }
    val previewStyle = normalized.copy(fontSizeSp = pendingFontSize.roundToInt()).normalized()
    val tokens = MaterialTheme.nuvio
    val darkBackground = tokens.colors.textPrimary.luminance() > 0.5f
    val selectedArgb = homeCatalogTitleColorArgb(normalized.colorId, darkBackground)
    val titleColor = selectedArgb?.let { Color(it) } ?: tokens.colors.textPrimary
    val fontFamily = if (normalized.fontId == "default") {
        MaterialTheme.typography.titleLarge.fontFamily
    } else {
        rememberSubtitleFontFamily(normalized.fontId)
    }
    val previewTextStyle = MaterialTheme.typography.titleLarge.copy(
        fontFamily = fontFamily,
        fontSize = previewStyle.fontSizeSp.sp,
        fontWeight = if (normalized.bold) FontWeight.Bold else FontWeight.SemiBold,
    )
    val fontSizeAccessibilityLabel = stringResource(Res.string.settings_homescreen_title_size)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f), RoundedCornerShape(14.dp))
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = stringResource(Res.string.settings_homescreen_title_preview),
            style = MaterialTheme.typography.labelMedium,
            color = tokens.colors.textMuted,
        )
        Text(
            text = title,
            modifier = Modifier.fillMaxWidth(),
            style = previewTextStyle,
            color = titleColor,
            maxLines = 2,
        )

        Spacer(Modifier.height(2.dp))
        SettingsNavigationRow(
            title = stringResource(Res.string.settings_homescreen_title_font),
            description = subtitleFontLabel(normalized.fontId),
            isTablet = isTablet,
            onClick = { showFonts = true },
        )

        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(Res.string.settings_homescreen_title_size),
                    style = MaterialTheme.typography.bodyLarge,
                    color = tokens.colors.textPrimary,
                )
                Text(
                    text = stringResource(Res.string.settings_homescreen_title_size_value, normalized.fontSizeSp),
                    style = MaterialTheme.typography.labelLarge,
                    color = tokens.colors.textMuted,
                )
            }
            Slider(
                value = pendingFontSize,
                onValueChange = { value -> pendingFontSize = value },
                onValueChangeFinished = {
                    onStyleChanged(normalized.copy(fontSizeSp = pendingFontSize.roundToInt()).normalized())
                },
                valueRange = HOME_CATALOG_TITLE_MIN_SIZE_SP.toFloat()..HOME_CATALOG_TITLE_MAX_SIZE_SP.toFloat(),
                steps = HOME_CATALOG_TITLE_MAX_SIZE_SP - HOME_CATALOG_TITLE_MIN_SIZE_SP - 1,
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = fontSizeAccessibilityLabel },
            )
        }

        SettingsSwitchRow(
            title = stringResource(Res.string.settings_homescreen_title_bold),
            checked = normalized.bold,
            isTablet = isTablet,
            horizontalPadding = 0.dp,
            onCheckedChange = { onStyleChanged(normalized.copy(bold = it)) },
        )

        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(Res.string.settings_homescreen_title_color),
                    style = MaterialTheme.typography.bodyLarge,
                    color = tokens.colors.textPrimary,
                )
                TextButton(
                    onClick = { onStyleChanged(normalized.copy(colorId = null)) },
                    enabled = normalized.colorId != null,
                ) {
                    Text(stringResource(Res.string.settings_homescreen_title_use_theme))
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                HOME_CATALOG_TITLE_COLOR_IDS.forEach { colorId ->
                    val argb = homeCatalogTitleColorArgb(colorId, darkBackground) ?: return@forEach
                    val color = Color(argb)
                    val selected = normalized.colorId == colorId
                    val label = stringResource(titleColorLabel(colorId))
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .selectable(
                                selected = selected,
                                role = Role.RadioButton,
                                onClick = { onStyleChanged(normalized.copy(colorId = colorId)) },
                            )
                            .semantics { contentDescription = label },
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(28.dp)
                                .clip(CircleShape)
                                .background(color)
                                .border(
                                    width = if (selected) 2.dp else 1.dp,
                                    color = if (selected) tokens.colors.textPrimary else tokens.colors.borderDefault,
                                    shape = CircleShape,
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (selected) {
                                androidx.compose.material3.Icon(
                                    imageVector = Icons.Rounded.Check,
                                    contentDescription = null,
                                    tint = if (color.luminance() > 0.55f) Color.Black else Color.White,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        }
                    }
                }
            }
        }

        Text(
            text = stringResource(Res.string.settings_homescreen_title_appearance_hint),
            style = MaterialTheme.typography.bodySmall,
            color = tokens.colors.textMuted,
        )
        TextButton(
            onClick = { onStyleChanged(HomeCatalogTitleStyle.DEFAULT) },
            enabled = !normalized.isDefault,
        ) {
            Text(stringResource(Res.string.settings_homescreen_title_style_reset))
        }
    }

    if (showFonts) {
        SubtitleFontPicker(
            selectedId = normalized.fontId,
            onSelect = { id -> onStyleChanged(normalized.copy(fontId = id).normalized()) },
            onDismiss = { showFonts = false },
        )
    }
}

@Composable
private fun titleColorLabel(colorId: String) = when (colorId) {
    "red" -> Res.string.settings_homescreen_title_color_red
    "orange" -> Res.string.settings_homescreen_title_color_orange
    "yellow" -> Res.string.settings_homescreen_title_color_yellow
    "green" -> Res.string.settings_homescreen_title_color_green
    "cyan" -> Res.string.settings_homescreen_title_color_cyan
    "blue" -> Res.string.settings_homescreen_title_color_blue
    "purple" -> Res.string.settings_homescreen_title_color_purple
    "pink" -> Res.string.settings_homescreen_title_color_pink
    else -> Res.string.settings_homescreen_title_color
}
