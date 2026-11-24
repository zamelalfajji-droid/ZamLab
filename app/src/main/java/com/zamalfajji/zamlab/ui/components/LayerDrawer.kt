package com.zamalfajji.zamlab.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.BlurOn
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Gradient
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zamalfajji.zamlab.R
import com.zamalfajji.zamlab.editor.ImageLayer
import com.zamalfajji.zamlab.editor.LayerBlendMode
import com.zamalfajji.zamlab.editor.LayerMaskBrushMode
import com.zamalfajji.zamlab.editor.LayerTransformMath
import com.zamalfajji.zamlab.ui.theme.StudioAmberPillFill
import com.zamalfajji.zamlab.ui.theme.StudioBackground
import com.zamalfajji.zamlab.ui.theme.StudioCinemaAmber
import com.zamalfajji.zamlab.ui.theme.StudioCoolWhite
import com.zamalfajji.zamlab.ui.theme.StudioEraserCrimson
import com.zamalfajji.zamlab.ui.theme.StudioFrostedBorder
import com.zamalfajji.zamlab.ui.theme.StudioFrostedGlass
import com.zamalfajji.zamlab.ui.theme.StudioGraphite
import com.zamalfajji.zamlab.ui.theme.StudioHairline
import com.zamalfajji.zamlab.ui.theme.StudioObsidian
import com.zamalfajji.zamlab.ui.theme.StudioTextMuted
import com.zamalfajji.zamlab.ui.theme.StudioTextPrimary
import com.zamalfajji.zamlab.ui.theme.StudioTextSecondary
import kotlin.math.roundToInt

/**
 * Persistent, collapsible OLED Layer Strip / Drawer docked above the bottom tool navigation dock.
 * Remains accessible during every editing mode (ADJUST, ALL, UPSCALE, DENOISE, LUTS, CROP, HEAL, MASKS)
 * so the user can add collage photo layers, toggle visibility, reorder z-order, adjust opacity,
 * delete overlay layers, and switch the active grading target with a single tap.
 */
@Composable
fun OledLayerDrawer(
    layers: List<ImageLayer>,
    activeLayerId: String?,
    isExpanded: Boolean,
    isSmartCutProcessing: Boolean = false,
    smartCutStatusLabel: String? = null,
    smartCutRemoveSubjectFromBg: Boolean = true,
    isLayerFxExpanded: Boolean = false,
    isLayerMaskBrushMode: Boolean = false,
    onToggleExpanded: () -> Unit,
    onSelectLayer: (String) -> Unit,
    onAddPhotoLayer: () -> Unit,
    onRunSmartCut: () -> Unit = {},
    onToggleSmartCutCleanBg: () -> Unit = {},
    onToggleLayerMaskBrush: () -> Unit = {},
    onToggleLayerFx: () -> Unit = {},
    onSelectBlendMode: (LayerBlendMode) -> Unit = {},
    onBokehBlurChange: (Float) -> Unit = {},
    onResetBokehBlur: () -> Unit = {},
    onEdgeFeatherChange: (Float) -> Unit = {},
    onResetEdgeFeather: () -> Unit = {},
    onToggleLayerVisibility: (String) -> Unit,
    onMoveLayerUp: (String) -> Unit,
    onMoveLayerDown: (String) -> Unit,
    onDeleteLayer: (String) -> Unit,
    onLayerOpacityChange: (String, Float) -> Unit,
    onLayerOpacityDraggingChanged: (Boolean) -> Unit,
    onResetActiveLayerTransform: () -> Unit,
    shape: Shape = RoundedCornerShape(topEnd = 18.dp, bottomEnd = 18.dp),
    modifier: Modifier = Modifier
) {
    val activeLayer = remember(layers, activeLayerId) {
        layers.firstOrNull { it.id == activeLayerId } ?: layers.firstOrNull()
    }
    // Display top-most z-order layer first on the left (Photoshop / studio layer stack convention)
    val displayLayers = remember(layers) { layers.asReversed() }

    Surface(
        shape = shape,
        color = StudioFrostedGlass,
        shadowElevation = 0.dp,
        modifier = modifier
            .fillMaxWidth()
            .border(1.dp, StudioFrostedBorder, shape)
            .testTag("oled_layer_drawer")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            // Compact Header Row: Layers Title + Count Badge + Active Layer Name + Collapse
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = 36.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(onClick = onToggleExpanded)
                        .padding(horizontal = 4.dp, vertical = 6.dp)
                        .testTag("toggle_layer_drawer_button")
                ) {
                    Icon(
                        imageVector = Icons.Filled.Layers,
                        contentDescription = stringResource(R.string.cd_toggle_layer_drawer),
                        tint = StudioCinemaAmber,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = stringResource(R.string.layers_header_label),
                        style = MaterialTheme.typography.labelSmall.copy(
                            color = StudioTextPrimary,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.8.sp,
                            fontSize = 10.sp
                        )
                    )
                    // Layer count badge
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(StudioAmberPillFill)
                            .border(1.dp, StudioCinemaAmber.copy(alpha = 0.45f), RoundedCornerShape(50))
                            .padding(horizontal = 6.dp, vertical = 1.dp)
                    ) {
                        Text(
                            text = layers.size.toString(),
                            style = MaterialTheme.typography.labelSmall.copy(
                                color = StudioCinemaAmber,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                fontSize = 10.sp
                            )
                        )
                    }

                    if (activeLayer != null) {
                        Text(
                            text = activeLayer.name,
                            style = MaterialTheme.typography.labelSmall.copy(
                                color = StudioTextSecondary,
                                fontSize = 11.sp
                            ),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                    }

                    Icon(
                        imageVector = if (isExpanded) {
                            Icons.Filled.KeyboardArrowDown
                        } else {
                            Icons.Filled.KeyboardArrowUp
                        },
                        contentDescription = stringResource(R.string.cd_toggle_layer_drawer),
                        tint = StudioTextSecondary,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }

            // Action Pill Row: Mask Brush | Smart Cut | + Add Photo Layer (horizontally scrollable for narrow slide-out width)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                    // Manual Erase / Restore Mask Brush toggle (next to Smart Cut)
                    val maskBrushEnabled = activeLayer != null && !isSmartCutProcessing
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = if (isLayerMaskBrushMode) StudioCinemaAmber else StudioObsidian,
                        modifier = Modifier
                            .border(
                                width = 1.dp,
                                color = when {
                                    isLayerMaskBrushMode -> StudioCinemaAmber
                                    maskBrushEnabled && (activeLayer.maskBitmap != null || !activeLayer.isBackground) ->
                                        StudioCinemaAmber.copy(alpha = 0.65f)
                                    else -> StudioHairline
                                },
                                shape = RoundedCornerShape(50)
                            )
                            .clip(RoundedCornerShape(50))
                            .clickable(enabled = maskBrushEnabled, onClick = onToggleLayerMaskBrush)
                            .testTag("layer_mask_brush_button")
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(3.dp),
                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 6.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Brush,
                                contentDescription = stringResource(R.string.cd_mask_brush),
                                tint = when {
                                    isLayerMaskBrushMode -> StudioBackground
                                    maskBrushEnabled -> StudioCinemaAmber
                                    else -> StudioTextMuted
                                },
                                modifier = Modifier.size(12.dp)
                            )
                            Text(
                                text = stringResource(R.string.action_mask_brush),
                                style = MaterialTheme.typography.labelSmall.copy(
                                    color = when {
                                        isLayerMaskBrushMode -> StudioBackground
                                        maskBrushEnabled -> StudioTextPrimary
                                        else -> StudioTextMuted
                                    },
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 10.sp
                                ),
                                maxLines = 1
                            )
                        }
                    }

                    // AI "Smart Cut" subject/background separation action pill
                    val smartCutEnabled = activeLayer != null && !isSmartCutProcessing
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = if (isSmartCutProcessing) StudioAmberPillFill else StudioObsidian,
                        modifier = Modifier
                            .border(
                                width = 1.dp,
                                color = if (smartCutEnabled || isSmartCutProcessing) {
                                    StudioCinemaAmber.copy(alpha = 0.72f)
                                } else {
                                    StudioHairline
                                },
                                shape = RoundedCornerShape(50)
                            )
                            .clip(RoundedCornerShape(50))
                            .clickable(enabled = smartCutEnabled, onClick = onRunSmartCut)
                            .testTag("smart_cut_button")
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(3.dp),
                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 6.dp)
                        ) {
                            if (isSmartCutProcessing) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(12.dp),
                                    strokeWidth = 1.6.dp,
                                    color = StudioCinemaAmber
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.Filled.AutoFixHigh,
                                    contentDescription = stringResource(R.string.cd_smart_cut),
                                    tint = if (smartCutEnabled) StudioCinemaAmber else StudioTextMuted,
                                    modifier = Modifier.size(12.dp)
                                )
                            }
                            Text(
                                text = stringResource(R.string.action_smart_cut),
                                style = MaterialTheme.typography.labelSmall.copy(
                                    color = if (smartCutEnabled || isSmartCutProcessing) {
                                        StudioTextPrimary
                                    } else {
                                        StudioTextMuted
                                    },
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 10.sp
                                ),
                                maxLines = 1
                            )
                        }
                    }

                    // "+ Add Photo Layer" compact OLED amber capsule action
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = StudioAmberPillFill,
                        modifier = Modifier
                            .border(1.dp, StudioCinemaAmber.copy(alpha = 0.65f), RoundedCornerShape(50))
                            .clip(RoundedCornerShape(50))
                            .clickable(onClick = onAddPhotoLayer)
                            .testTag("add_photo_layer_button")
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(3.dp),
                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 6.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.AddPhotoAlternate,
                                contentDescription = stringResource(R.string.cd_add_photo_layer),
                                tint = StudioCinemaAmber,
                                modifier = Modifier.size(12.dp)
                            )
                            Text(
                                text = stringResource(R.string.action_add_photo_layer),
                                style = MaterialTheme.typography.labelSmall.copy(
                                    color = StudioCinemaAmber,
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 10.sp
                                ),
                                maxLines = 1
                            )
                        }
                    }
                }

            // OLED Smart Cut Processing Status Bar
            AnimatedVisibility(
                visible = isSmartCutProcessing,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp, bottom = 2.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(StudioObsidian)
                        .border(1.dp, StudioCinemaAmber.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                        .testTag("smart_cut_progress_indicator")
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        strokeWidth = 2.dp,
                        color = StudioCinemaAmber
                    )
                    Text(
                        text = "AI SMART CUT · ${(smartCutStatusLabel ?: stringResource(R.string.smart_cut_processing_default)).uppercase()}",
                        style = MaterialTheme.typography.labelSmall.copy(
                            color = StudioCinemaAmber,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.SemiBold,
                            letterSpacing = 0.5.sp,
                            fontSize = 10.sp
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            AnimatedVisibility(
                visible = isExpanded,
                enter = fadeIn() + expandVertically(
                    animationSpec = spring(stiffness = Spring.StiffnessMediumLow)
                ),
                exit = fadeOut() + shrinkVertically(
                    animationSpec = spring(stiffness = Spring.StiffnessMedium)
                )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp, bottom = 2.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    LazyRow(
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("layer_cards_row"),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        contentPadding = PaddingValues(horizontal = 2.dp)
                    ) {
                        itemsIndexed(
                            items = displayLayers,
                            key = { _, item -> item.id }
                        ) { displayIdx, layer ->
                            // Real stack index: 0 = bottom, layers.lastIndex = top
                            val stackIndex = layers.lastIndex - displayIdx
                            val isActive = layer.id == activeLayer?.id
                            val canMoveUp = stackIndex < layers.lastIndex
                            val canMoveDown = stackIndex > 0
                            val canDelete = !layer.isBackground && layers.size > 1

                            OledLayerCard(
                                layer = layer,
                                stackPositionLabel = if (layer.isBackground) {
                                    "BG"
                                } else {
                                    "L${stackIndex + 1}"
                                },
                                isActive = isActive,
                                canMoveUp = canMoveUp,
                                canMoveDown = canMoveDown,
                                canDelete = canDelete,
                                onSelect = { onSelectLayer(layer.id) },
                                onToggleVisibility = { onToggleLayerVisibility(layer.id) },
                                onMoveUp = { onMoveLayerUp(layer.id) },
                                onMoveDown = { onMoveLayerDown(layer.id) },
                                onDelete = { onDeleteLayer(layer.id) }
                            )
                        }
                    }

                    // Compact Layer FX toggle + Smart Cut Clean BG toggle + active-layer opacity & transform reset bar
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        // Collapsible "Layer FX" toggle chip (Blend Mode, Bokeh Blur, Edge Feather)
                        val hasFx = activeLayer?.hasLayerFx == true
                        Surface(
                            shape = RoundedCornerShape(50),
                            color = when {
                                isLayerFxExpanded -> StudioCinemaAmber
                                hasFx -> StudioAmberPillFill
                                else -> StudioObsidian
                            },
                            modifier = Modifier
                                .border(
                                    width = 1.dp,
                                    color = if (isLayerFxExpanded || hasFx) {
                                        StudioCinemaAmber.copy(alpha = 0.78f)
                                    } else {
                                        StudioHairline
                                    },
                                    shape = RoundedCornerShape(50)
                                )
                                .clip(RoundedCornerShape(50))
                                .clickable(enabled = activeLayer != null, onClick = onToggleLayerFx)
                                .testTag("layer_fx_toggle_button")
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(3.dp),
                                modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Tune,
                                    contentDescription = stringResource(R.string.cd_layer_fx),
                                    tint = when {
                                        isLayerFxExpanded -> StudioBackground
                                        hasFx -> StudioCinemaAmber
                                        else -> StudioTextSecondary
                                    },
                                    modifier = Modifier.size(11.dp)
                                )
                                Text(
                                    text = stringResource(R.string.action_layer_fx),
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        color = when {
                                            isLayerFxExpanded -> StudioBackground
                                            hasFx -> StudioCinemaAmber
                                            else -> StudioTextSecondary
                                        },
                                        fontWeight = if (isLayerFxExpanded || hasFx) FontWeight.SemiBold else FontWeight.Normal,
                                        fontSize = 10.sp
                                    ),
                                    maxLines = 1
                                )
                                if (hasFx && !isLayerFxExpanded) {
                                    Box(
                                        modifier = Modifier
                                            .size(5.dp)
                                            .clip(CircleShape)
                                            .background(StudioCinemaAmber)
                                    )
                                }
                            }
                        }

                        // Clean BG (Remove / Inpaint Subject from Background) mode chip
                        Surface(
                            shape = RoundedCornerShape(50),
                            color = if (smartCutRemoveSubjectFromBg) StudioAmberPillFill else StudioObsidian,
                            modifier = Modifier
                                .border(
                                    width = 1.dp,
                                    color = if (smartCutRemoveSubjectFromBg) {
                                        StudioCinemaAmber.copy(alpha = 0.6f)
                                    } else {
                                        StudioHairline
                                    },
                                    shape = RoundedCornerShape(50)
                                )
                                .clip(RoundedCornerShape(50))
                                .clickable(enabled = !isSmartCutProcessing, onClick = onToggleSmartCutCleanBg)
                                .testTag("smart_cut_clean_bg_toggle")
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(3.dp),
                                modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.AutoAwesome,
                                    contentDescription = stringResource(R.string.cd_smart_cut_clean_bg),
                                    tint = if (smartCutRemoveSubjectFromBg) StudioCinemaAmber else StudioTextMuted,
                                    modifier = Modifier.size(11.dp)
                                )
                                Text(
                                    text = stringResource(R.string.smart_cut_clean_bg_label),
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        color = if (smartCutRemoveSubjectFromBg) StudioCinemaAmber else StudioTextSecondary,
                                        fontWeight = if (smartCutRemoveSubjectFromBg) FontWeight.SemiBold else FontWeight.Normal,
                                        fontSize = 10.sp
                                    ),
                                    maxLines = 1
                                )
                            }
                        }

                        if (activeLayer != null && layers.size > 1) {
                            Text(
                                text = stringResource(R.string.layer_opacity_label),
                                style = MaterialTheme.typography.labelSmall.copy(
                                    color = StudioTextSecondary,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Medium
                                )
                            )
                            Slider(
                                value = activeLayer.opacity,
                                onValueChange = { next ->
                                    onLayerOpacityDraggingChanged(true)
                                    onLayerOpacityChange(activeLayer.id, next)
                                },
                                onValueChangeFinished = {
                                    onLayerOpacityDraggingChanged(false)
                                },
                                valueRange = 0f..1f,
                                colors = SliderDefaults.colors(
                                    thumbColor = StudioCinemaAmber,
                                    activeTrackColor = StudioCinemaAmber,
                                    inactiveTrackColor = StudioHairline
                                ),
                                modifier = Modifier
                                    .weight(1f)
                                    .height(26.dp)
                                    .testTag("active_layer_opacity_slider")
                            )
                            Text(
                                text = "${(activeLayer.opacity * 100f).roundToInt()}%",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    color = StudioCinemaAmber,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 10.sp
                                )
                            )
                            if (activeLayer.hasNonDefaultTransform) {
                                Surface(
                                    shape = RoundedCornerShape(50),
                                    color = StudioObsidian,
                                    modifier = Modifier
                                        .border(1.dp, StudioHairline, RoundedCornerShape(50))
                                        .clip(RoundedCornerShape(50))
                                        .clickable(onClick = onResetActiveLayerTransform)
                                        .testTag("reset_layer_transform_button")
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(3.dp),
                                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Filled.RestartAlt,
                                            contentDescription = stringResource(R.string.cd_reset_layer_transform),
                                            tint = StudioCoolWhite,
                                            modifier = Modifier.size(12.dp)
                                        )
                                        Text(
                                            text = stringResource(R.string.action_reset_short),
                                            style = MaterialTheme.typography.labelSmall.copy(
                                                color = StudioCoolWhite,
                                                fontSize = 10.sp
                                            )
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // Collapsible "Layer FX" panel: Blend Mode selector + Bokeh Blur & Edge Feather OLED rulers
                    AnimatedVisibility(
                        visible = isLayerFxExpanded && activeLayer != null,
                        enter = fadeIn() + expandVertically(
                            animationSpec = spring(stiffness = Spring.StiffnessMediumLow)
                        ),
                        exit = fadeOut() + shrinkVertically(
                            animationSpec = spring(stiffness = Spring.StiffnessMedium)
                        )
                    ) {
                        if (activeLayer != null) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 4.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(Color(0xFF0E0E12).copy(alpha = 0.56f))
                                    .border(1.dp, StudioFrostedBorder, RoundedCornerShape(10.dp))
                                    .padding(horizontal = 8.dp, vertical = 6.dp)
                                    .testTag("layer_fx_panel"),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                // Blend Mode pill row
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(
                                        text = stringResource(R.string.layer_blend_mode_label).uppercase(),
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            color = StudioTextSecondary,
                                            fontWeight = FontWeight.Bold,
                                            letterSpacing = 0.6.sp,
                                            fontSize = 9.sp
                                        )
                                    )
                                    Row(
                                        modifier = Modifier
                                            .weight(1f)
                                            .horizontalScroll(rememberScrollState()),
                                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        LayerBlendMode.entries.forEach { mode ->
                                            val isSelected = activeLayer.blendMode == mode
                                            Surface(
                                                shape = RoundedCornerShape(50),
                                                color = if (isSelected) StudioCinemaAmber else StudioGraphite,
                                                modifier = Modifier
                                                    .border(
                                                        width = 1.dp,
                                                        color = if (isSelected) StudioCinemaAmber else StudioHairline,
                                                        shape = RoundedCornerShape(50)
                                                    )
                                                    .clip(RoundedCornerShape(50))
                                                    .clickable { onSelectBlendMode(mode) }
                                                    .testTag("blend_mode_${mode.name.lowercase()}")
                                            ) {
                                                Text(
                                                    text = mode.label,
                                                    style = MaterialTheme.typography.labelSmall.copy(
                                                        color = if (isSelected) StudioBackground else StudioTextPrimary,
                                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                                        fontSize = 10.sp
                                                    ),
                                                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp)
                                                )
                                            }
                                        }
                                    }
                                }

                                // Bokeh Blur OLED Precision Ruler Slider (0-100% -> 0f..25f)
                                val bokehPercent = ((activeLayer.bokehBlurRadius / LayerTransformMath.MAX_BOKEH_BLUR_RADIUS) * 100f)
                                    .roundToInt()
                                    .coerceIn(0, 100)
                                RulerPrecisionSlider(
                                    label = stringResource(R.string.layer_bokeh_blur_label),
                                    icon = Icons.Filled.BlurOn,
                                    value = activeLayer.bokehBlurRadius,
                                    valueRange = 0f..LayerTransformMath.MAX_BOKEH_BLUR_RADIUS,
                                    step = 0.25f,
                                    defaultValue = 0f,
                                    formattedValue = "$bokehPercent%",
                                    isModified = activeLayer.bokehBlurRadius > 0.05f,
                                    testTag = "layer_bokeh_blur_slider",
                                    onValueChange = { next ->
                                        onLayerOpacityDraggingChanged(true)
                                        onBokehBlurChange(next)
                                    },
                                    onValueChangeFinished = {
                                        onLayerOpacityDraggingChanged(false)
                                    },
                                    onReset = onResetBokehBlur
                                )

                                // Edge Feather OLED Precision Ruler Slider (0-100% -> 0f..20f)
                                val featherPercent = ((activeLayer.edgeFeather / LayerTransformMath.MAX_EDGE_FEATHER) * 100f)
                                    .roundToInt()
                                    .coerceIn(0, 100)
                                RulerPrecisionSlider(
                                    label = stringResource(R.string.layer_edge_feather_label),
                                    icon = Icons.Filled.Gradient,
                                    value = activeLayer.edgeFeather,
                                    valueRange = 0f..LayerTransformMath.MAX_EDGE_FEATHER,
                                    step = 0.2f,
                                    defaultValue = 0f,
                                    formattedValue = "$featherPercent%",
                                    isModified = activeLayer.edgeFeather > 0.05f,
                                    testTag = "layer_edge_feather_slider",
                                    onValueChange = { next ->
                                        onLayerOpacityDraggingChanged(true)
                                        onEdgeFeatherChange(next)
                                    },
                                    onValueChangeFinished = {
                                        onLayerOpacityDraggingChanged(false)
                                    },
                                    onReset = onResetEdgeFeather
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Compact OLED floating Mask Brush control bar displayed over the bottom of the photo viewport
 * while manual Erase / Restore Mask Brush mode is active.
 */
@Composable
fun OledMaskBrushBar(
    brushMode: LayerMaskBrushMode,
    brushSizeDp: Float,
    brushSoftness: Float,
    activeLayerName: String?,
    onSelectMode: (LayerMaskBrushMode) -> Unit,
    onBrushSizeChange: (Float) -> Unit,
    onBrushSoftnessChange: (Float) -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = StudioFrostedGlass,
        shadowElevation = 0.dp,
        modifier = modifier
            .widthIn(max = 420.dp)
            .fillMaxWidth()
            .border(1.dp, StudioFrostedBorder, RoundedCornerShape(16.dp))
            .testTag("layer_mask_brush_bar")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            // Top Row: Erase / Restore segmented pills + Active layer badge + Done button
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    MaskBrushModePill(
                        label = stringResource(R.string.mask_brush_erase),
                        selected = brushMode == LayerMaskBrushMode.ERASE,
                        accentColor = StudioEraserCrimson,
                        testTag = "mask_brush_mode_erase",
                        onClick = { onSelectMode(LayerMaskBrushMode.ERASE) }
                    )
                    MaskBrushModePill(
                        label = stringResource(R.string.mask_brush_restore),
                        selected = brushMode == LayerMaskBrushMode.RESTORE,
                        accentColor = StudioCinemaAmber,
                        testTag = "mask_brush_mode_restore",
                        onClick = { onSelectMode(LayerMaskBrushMode.RESTORE) }
                    )
                }

                if (!activeLayerName.isNullOrBlank()) {
                    Text(
                        text = activeLayerName,
                        style = MaterialTheme.typography.labelSmall.copy(
                            color = StudioTextSecondary,
                            fontSize = 10.sp
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .weight(1f, fill = false)
                            .padding(horizontal = 6.dp)
                    )
                }

                Surface(
                    shape = RoundedCornerShape(50),
                    color = StudioCinemaAmber,
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .clickable(onClick = onDone)
                        .testTag("mask_brush_done_button")
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(3.dp),
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Check,
                            contentDescription = stringResource(R.string.cd_mask_brush_done),
                            tint = StudioBackground,
                            modifier = Modifier.size(13.dp)
                        )
                        Text(
                            text = stringResource(R.string.action_done),
                            style = MaterialTheme.typography.labelSmall.copy(
                                color = StudioBackground,
                                fontWeight = FontWeight.Bold,
                                fontSize = 11.sp
                            )
                        )
                    }
                }
            }

            // Bottom Row: Size (10dp..120dp) + Softness (0%..100%) sliders side-by-side
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    Text(
                        text = stringResource(R.string.mask_brush_size),
                        style = MaterialTheme.typography.labelSmall.copy(
                            color = StudioTextSecondary,
                            fontSize = 10.sp
                        )
                    )
                    Slider(
                        value = brushSizeDp,
                        onValueChange = onBrushSizeChange,
                        valueRange = LayerTransformMath.MIN_MASK_BRUSH_SIZE_DP..LayerTransformMath.MAX_MASK_BRUSH_SIZE_DP,
                        colors = SliderDefaults.colors(
                            thumbColor = StudioCinemaAmber,
                            activeTrackColor = StudioCinemaAmber,
                            inactiveTrackColor = StudioHairline
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .height(24.dp)
                            .testTag("mask_brush_size_slider")
                    )
                    Text(
                        text = "${brushSizeDp.roundToInt()}dp",
                        style = MaterialTheme.typography.labelSmall.copy(
                            color = StudioCinemaAmber,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 10.sp
                        )
                    )
                }

                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    Text(
                        text = stringResource(R.string.mask_brush_softness),
                        style = MaterialTheme.typography.labelSmall.copy(
                            color = StudioTextSecondary,
                            fontSize = 10.sp
                        )
                    )
                    Slider(
                        value = brushSoftness,
                        onValueChange = onBrushSoftnessChange,
                        valueRange = 0f..1f,
                        colors = SliderDefaults.colors(
                            thumbColor = StudioCinemaAmber,
                            activeTrackColor = StudioCinemaAmber,
                            inactiveTrackColor = StudioHairline
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .height(24.dp)
                            .testTag("mask_brush_softness_slider")
                    )
                    Text(
                        text = "${(brushSoftness * 100f).roundToInt()}%",
                        style = MaterialTheme.typography.labelSmall.copy(
                            color = StudioCinemaAmber,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 10.sp
                        )
                    )
                }
            }
        }
    }
}

@Composable
private fun MaskBrushModePill(
    label: String,
    selected: Boolean,
    accentColor: Color,
    testTag: String,
    onClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(50),
        color = if (selected) accentColor.copy(alpha = 0.22f) else StudioObsidian,
        modifier = Modifier
            .border(
                width = 1.dp,
                color = if (selected) accentColor else StudioHairline,
                shape = RoundedCornerShape(50)
            )
            .clip(RoundedCornerShape(50))
            .clickable(onClick = onClick)
            .testTag(testTag)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall.copy(
                color = if (selected) accentColor else StudioTextSecondary,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                fontSize = 11.sp
            ),
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
        )
    }
}

@Composable
private fun OledLayerCard(
    layer: ImageLayer,
    stackPositionLabel: String,
    isActive: Boolean,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    canDelete: Boolean,
    onSelect: () -> Unit,
    onToggleVisibility: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onDelete: () -> Unit
) {
    val borderColor by animateColorAsState(
        targetValue = if (isActive) StudioCinemaAmber else StudioHairline,
        label = "layer_card_border"
    )
    val cardBackground by animateColorAsState(
        targetValue = if (isActive) {
            Color(0xFF1F1A12).copy(alpha = 0.78f)
        } else {
            Color(0xFF0E0E12).copy(alpha = 0.62f)
        },
        label = "layer_card_bg"
    )
    val thumbBitmap = remember(layer.thumbnailBitmap, layer.renderedPreviewBitmap, layer.bitmap) {
        (layer.thumbnailBitmap?.takeIf { !it.isRecycled }
            ?: layer.renderedPreviewBitmap?.takeIf { !it.isRecycled }
            ?: layer.bitmap.takeIf { !it.isRecycled })
            ?.asImageBitmap()
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(cardBackground)
            .border(
                width = if (isActive) 1.5.dp else 1.dp,
                color = borderColor,
                shape = RoundedCornerShape(10.dp)
            )
            .clickable(onClick = onSelect)
            .padding(horizontal = 6.dp, vertical = 5.dp)
            .testTag("layer_card_${layer.id}")
    ) {
        // Live Layer Thumbnail with subtle transparency checkerboard
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(7.dp))
                .border(1.dp, if (isActive) StudioCinemaAmber.copy(alpha = 0.7f) else StudioHairline, RoundedCornerShape(7.dp))
        ) {
            LayerThumbCheckerboard(modifier = Modifier.fillMaxSize())
            if (thumbBitmap != null) {
                Image(
                    bitmap = thumbBitmap,
                    contentDescription = stringResource(R.string.cd_layer_thumbnail, layer.name),
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxSize()
                        .alpha(if (layer.isVisible) layer.opacity.coerceAtLeast(0.15f) else 0.25f)
                )
            }
            // Stack badge (BG / L2 / L3)
            Box(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(2.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(StudioBackground.copy(alpha = 0.82f))
                    .padding(horizontal = 3.dp, vertical = 1.dp)
            ) {
                Text(
                    text = stackPositionLabel,
                    style = MaterialTheme.typography.labelSmall.copy(
                        color = if (isActive) StudioCinemaAmber else StudioTextPrimary,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        fontSize = 8.sp
                    )
                )
            }
            if (layer.hasLayerFx) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(2.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(StudioCinemaAmber.copy(alpha = 0.92f))
                        .padding(horizontal = 3.dp, vertical = 0.5.dp)
                ) {
                    Text(
                        text = if (layer.blendMode != LayerBlendMode.NORMAL) {
                            layer.blendMode.name.take(3)
                        } else {
                            "FX"
                        },
                        style = MaterialTheme.typography.labelSmall.copy(
                            color = StudioBackground,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = 7.sp
                        )
                    )
                }
            }
        }

        Column(
            verticalArrangement = Arrangement.spacedBy(3.dp),
            modifier = Modifier.width(104.dp)
        ) {
            Text(
                text = layer.name,
                style = MaterialTheme.typography.labelSmall.copy(
                    color = if (isActive) StudioTextPrimary else StudioTextSecondary,
                    fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal,
                    fontSize = 11.sp
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                // Visibility eye toggle
                LayerMiniActionIcon(
                    onClick = onToggleVisibility,
                    enabled = true,
                    contentDescription = stringResource(R.string.cd_toggle_layer_visibility, layer.name),
                    testTag = "layer_visibility_${layer.id}",
                    tint = if (layer.isVisible) StudioCinemaAmber else StudioTextMuted,
                    icon = if (layer.isVisible) Icons.Filled.Visibility else Icons.Filled.VisibilityOff
                )

                // Move layer up (forward in z-order)
                LayerMiniActionIcon(
                    onClick = onMoveUp,
                    enabled = canMoveUp,
                    contentDescription = stringResource(R.string.cd_move_layer_up, layer.name),
                    testTag = "layer_move_up_${layer.id}",
                    tint = if (canMoveUp) StudioCoolWhite else StudioTextMuted.copy(alpha = 0.35f),
                    icon = Icons.Filled.KeyboardArrowUp
                )

                // Move layer down (backward in z-order)
                LayerMiniActionIcon(
                    onClick = onMoveDown,
                    enabled = canMoveDown,
                    contentDescription = stringResource(R.string.cd_move_layer_down, layer.name),
                    testTag = "layer_move_down_${layer.id}",
                    tint = if (canMoveDown) StudioCoolWhite else StudioTextMuted.copy(alpha = 0.35f),
                    icon = Icons.Filled.KeyboardArrowDown
                )

                // Delete overlay layer
                if (canDelete) {
                    LayerMiniActionIcon(
                        onClick = onDelete,
                        enabled = true,
                        contentDescription = stringResource(R.string.cd_delete_layer, layer.name),
                        testTag = "layer_delete_${layer.id}",
                        tint = StudioEraserCrimson,
                        icon = Icons.Filled.DeleteOutline
                    )
                }
            }
        }
    }
}

@Composable
private fun LayerMiniActionIcon(
    onClick: () -> Unit,
    enabled: Boolean,
    contentDescription: String,
    testTag: String,
    tint: Color,
    icon: androidx.compose.ui.graphics.vector.ImageVector
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(24.dp)
            .clip(CircleShape)
            .clickable(enabled = enabled, onClick = onClick)
            .testTag(testTag)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = tint,
            modifier = Modifier.size(15.dp)
        )
    }
}

@Composable
private fun LayerThumbCheckerboard(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val cell = 6.dp.toPx()
        val cols = (size.width / cell).toInt() + 1
        val rows = (size.height / cell).toInt() + 1
        val dark = Color(0xFF141418)
        val light = Color(0xFF222228)
        for (r in 0 until rows) {
            for (c in 0 until cols) {
                drawRect(
                    color = if ((r + c) % 2 == 0) dark else light,
                    topLeft = Offset(c * cell, r * cell),
                    size = Size(cell, cell)
                )
            }
        }
    }
}

/**
 * Compact OLED floating layer pill docked at the bottom-left edge of the photo canvas.
 * Displays the active layer live thumbnail, layer name, and layer count badge (`Layers (N) ▾`).
 * Tapping expands the full floating [OledLayerDrawer] card over the lower canvas.
 */
@Composable
fun FloatingLayerPill(
    layers: List<ImageLayer>,
    activeLayerId: String?,
    isSmartCutProcessing: Boolean = false,
    isLayerMaskBrushMode: Boolean = false,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val activeLayer = remember(layers, activeLayerId) {
        layers.firstOrNull { it.id == activeLayerId } ?: layers.firstOrNull()
    }
    val layerCount = layers.size.coerceAtLeast(1)
    val activeName = activeLayer?.name ?: "Background"
    val previewBmp = remember(activeLayer?.thumbnailBitmap, activeLayer?.renderedPreviewBitmap, activeLayer?.bitmap) {
        activeLayer?.thumbnailBitmap?.takeIf { !it.isRecycled }
            ?: activeLayer?.renderedPreviewBitmap?.takeIf { !it.isRecycled }
            ?: activeLayer?.bitmap?.takeIf { !it.isRecycled }
    }
    val pillShape = RoundedCornerShape(24.dp)
    val pillCd = stringResource(R.string.cd_floating_layer_pill, layerCount, activeName)

    Surface(
        shape = pillShape,
        color = StudioGraphite.copy(alpha = 0.94f),
        shadowElevation = 8.dp,
        modifier = modifier
            .defaultMinSize(minHeight = 48.dp)
            .border(
                width = 1.dp,
                color = if (isSmartCutProcessing || isLayerMaskBrushMode) {
                    StudioCinemaAmber.copy(alpha = 0.72f)
                } else {
                    StudioHairline
                },
                shape = pillShape
            )
            .clip(pillShape)
            .clickable(onClick = onClick)
            .testTag("floating_layer_pill")
    ) {
        Row(
            modifier = Modifier.padding(start = 7.dp, end = 10.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Active Layer Thumbnail (28dp rounded square) or Smart Cut progress indicator
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .border(1.dp, StudioCinemaAmber.copy(alpha = 0.65f), RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center
            ) {
                LayerThumbCheckerboard(modifier = Modifier.fillMaxSize())
                if (previewBmp != null) {
                    Image(
                        bitmap = previewBmp.asImageBitmap(),
                        contentDescription = pillCd,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Icon(
                        imageVector = Icons.Filled.Layers,
                        contentDescription = pillCd,
                        tint = StudioCinemaAmber,
                        modifier = Modifier.size(15.dp)
                    )
                }
                if (isSmartCutProcessing) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.62f)),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(
                            color = StudioCinemaAmber,
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }

            Column(
                verticalArrangement = Arrangement.spacedBy(1.dp)
            ) {
                Text(
                    text = activeName,
                    style = MaterialTheme.typography.labelSmall.copy(
                        color = StudioTextPrimary,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 11.sp,
                        lineHeight = 13.sp
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 104.dp)
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    Text(
                        text = stringResource(R.string.floating_layer_pill_badge, layerCount),
                        style = MaterialTheme.typography.labelSmall.copy(
                            color = StudioCinemaAmber,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = 10.sp,
                            lineHeight = 12.sp
                        ),
                        maxLines = 1
                    )
                    if (isLayerMaskBrushMode) {
                        Box(
                            modifier = Modifier
                                .size(5.dp)
                                .clip(CircleShape)
                                .background(StudioCinemaAmber)
                        )
                    }
                }
            }

            Icon(
                imageVector = Icons.Filled.KeyboardArrowDown,
                contentDescription = null,
                tint = StudioCinemaAmber,
                modifier = Modifier.size(16.dp)
            )
        }
    }
}

/**
 * Slim, low-profile translucent glass handle tab docked flush on the middle-left edge of the canvas.
 * Shows "LAYERS ▸" when collapsed (plus amber count badge when multiple layers exist) and
 * slides the translucent [OledLayerDrawer] cleanly in and out from the left edge.
 */
@Composable
fun LeftEdgeLayersHandle(
    layerCount: Int,
    isExpanded: Boolean,
    isSmartCutProcessing: Boolean = false,
    isLayerMaskBrushMode: Boolean = false,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val tabShape = RoundedCornerShape(topEnd = 14.dp, bottomEnd = 14.dp)
    val count = layerCount.coerceAtLeast(1)
    val handleCd = stringResource(R.string.cd_left_layers_handle, count)
    val borderColor = when {
        isExpanded || isSmartCutProcessing || isLayerMaskBrushMode -> StudioCinemaAmber.copy(alpha = 0.68f)
        else -> StudioFrostedBorder
    }

    Surface(
        shape = tabShape,
        color = StudioFrostedGlass,
        shadowElevation = 0.dp,
        modifier = modifier
            .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
            .border(1.dp, borderColor, tabShape)
            .clip(tabShape)
            .semantics { contentDescription = handleCd }
            .clickable(onClick = onClick)
            .testTag("left_layers_edge_handle")
    ) {
        Row(
            modifier = Modifier.padding(
                start = if (isExpanded) 8.dp else 9.dp,
                end = if (isExpanded) 8.dp else 10.dp,
                top = 12.dp,
                bottom = 12.dp
            ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            if (isSmartCutProcessing) {
                CircularProgressIndicator(
                    color = StudioCinemaAmber,
                    strokeWidth = 1.8.dp,
                    modifier = Modifier.size(12.dp)
                )
            }
            if (!isExpanded) {
                Text(
                    text = "${stringResource(R.string.edge_handle_layers)} ▸",
                    style = MaterialTheme.typography.labelSmall.copy(
                        color = StudioCoolWhite,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.7.sp,
                        fontSize = 10.sp
                    ),
                    maxLines = 1,
                    softWrap = false
                )
                if (count > 1) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(StudioAmberPillFill)
                            .border(1.dp, StudioCinemaAmber.copy(alpha = 0.55f), RoundedCornerShape(50))
                            .padding(horizontal = 4.5.dp, vertical = 0.5.dp)
                    ) {
                        Text(
                            text = count.toString(),
                            style = MaterialTheme.typography.labelSmall.copy(
                                color = StudioCinemaAmber,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                fontSize = 9.sp
                            )
                        )
                    }
                }
            } else {
                Text(
                    text = "◂",
                    style = MaterialTheme.typography.labelMedium.copy(
                        color = StudioCinemaAmber,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp
                    ),
                    maxLines = 1,
                    softWrap = false
                )
            }
        }
    }
}

/**
 * Matching slim, low-profile translucent glass handle tab docked flush on the middle-right edge of the canvas.
 * Shows "TOOLS ◂" when collapsed and slides the translucent Tool / Inspector drawer smoothly in and out
 * from the right edge without crowding the bottom of the screen.
 */
@Composable
fun RightEdgeToolsHandle(
    activeTabLabel: String,
    isExpanded: Boolean,
    hasActiveEdits: Boolean = false,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val tabShape = RoundedCornerShape(topStart = 14.dp, bottomStart = 14.dp)
    val handleCd = stringResource(R.string.cd_right_tools_handle, activeTabLabel)
    val borderColor = if (isExpanded) {
        StudioCinemaAmber.copy(alpha = 0.68f)
    } else {
        StudioFrostedBorder
    }

    Surface(
        shape = tabShape,
        color = StudioFrostedGlass,
        shadowElevation = 0.dp,
        modifier = modifier
            .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
            .border(1.dp, borderColor, tabShape)
            .clip(tabShape)
            .semantics { contentDescription = handleCd }
            .clickable(onClick = onClick)
            .testTag("right_tools_edge_handle")
    ) {
        Row(
            modifier = Modifier.padding(
                start = if (isExpanded) 8.dp else 10.dp,
                end = if (isExpanded) 8.dp else 9.dp,
                top = 12.dp,
                bottom = 12.dp
            ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            if (!isExpanded) {
                if (hasActiveEdits) {
                    Box(
                        modifier = Modifier
                            .size(5.dp)
                            .clip(CircleShape)
                            .background(StudioCinemaAmber)
                    )
                }
                Text(
                    text = "${stringResource(R.string.edge_handle_tools)} ◂",
                    style = MaterialTheme.typography.labelSmall.copy(
                        color = StudioCoolWhite,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.7.sp,
                        fontSize = 10.sp
                    ),
                    maxLines = 1,
                    softWrap = false
                )
            } else {
                Text(
                    text = "▸",
                    style = MaterialTheme.typography.labelMedium.copy(
                        color = StudioCinemaAmber,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp
                    ),
                    maxLines = 1,
                    softWrap = false
                )
            }
        }
    }
}


