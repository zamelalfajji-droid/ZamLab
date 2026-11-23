package com.example.ui.components

import android.graphics.Bitmap
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zamalfajji.zamlab.R
import com.example.editor.CameraProfile
import com.example.editor.CameraProfiles
import com.example.editor.ProfileCategory
import com.example.ui.theme.StudioAmber
import com.example.ui.theme.StudioBackground
import com.example.ui.theme.StudioBorder
import com.example.ui.theme.StudioBorderSubtle
import com.example.ui.theme.StudioCyan
import com.example.ui.theme.StudioSurface
import com.example.ui.theme.StudioSurfaceElevated
import com.example.ui.theme.StudioSurfaceHighest
import com.example.ui.theme.StudioTextMuted
import com.example.ui.theme.StudioTextPrimary
import com.example.ui.theme.StudioTextSecondary
import kotlin.math.roundToInt

@Composable
fun ProfileSelectorBar(
    profileId: String,
    profileAmount: Float,
    onOpenMenu: () -> Unit,
    onOpenBrowser: () -> Unit,
    onAmountChange: (Float) -> Unit,
    onDraggingChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val activeProfile = remember(profileId) { CameraProfiles.byId(profileId) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Color(0xFF151923))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = StudioSurfaceElevated,
                modifier = Modifier
                    .weight(1f)
                    .defaultMinSize(minHeight = 48.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .border(
                        width = 1.dp,
                        color = if (activeProfile.isCreative) StudioAmber.copy(alpha = 0.65f) else StudioBorderSubtle,
                        shape = RoundedCornerShape(10.dp)
                    )
                    .clickable(onClick = onOpenMenu)
                    .testTag("profile_selector_button")
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(
                            text = "Profile:",
                            style = MaterialTheme.typography.labelMedium,
                            color = StudioTextSecondary
                        )
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .clip(CircleShape)
                                .background(Color(activeProfile.swatchArgb))
                                .border(1.dp, StudioBorder, CircleShape)
                        )
                        Text(
                            text = activeProfile.name,
                            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                            color = if (activeProfile.isCreative) StudioAmber else StudioTextPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Icon(
                        imageVector = Icons.Default.KeyboardArrowDown,
                        contentDescription = stringResource(R.string.cd_open_profile_menu),
                        tint = StudioTextSecondary,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            Surface(
                shape = RoundedCornerShape(10.dp),
                color = StudioSurfaceElevated,
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .border(1.dp, StudioBorderSubtle, RoundedCornerShape(10.dp))
            ) {
                IconButton(
                    onClick = onOpenBrowser,
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag("profile_browser_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.GridView,
                        contentDescription = stringResource(R.string.cd_open_profile_browser),
                        tint = StudioCyan,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }

        if (activeProfile.isCreative) {
            ProfileAmountRow(
                amount = profileAmount,
                onAmountChange = onAmountChange,
                onDraggingChanged = onDraggingChanged
            )
        }
    }
}

@Composable
private fun ProfileAmountRow(
    amount: Float,
    onAmountChange: (Float) -> Unit,
    onDraggingChanged: (Boolean) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 2.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .doubleTapToReset(
                    onDoubleTapReset = {
                        onDraggingChanged(false)
                        onAmountChange(1f)
                    }
                )
                .padding(vertical = 2.dp)
                .testTag("slider_profile_amount_header"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "Amount",
                style = MaterialTheme.typography.labelMedium,
                color = StudioTextSecondary
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = "${(amount * 100f).roundToInt()}%",
                    style = MaterialTheme.typography.labelMedium,
                    color = StudioAmber
                )
                if (kotlin.math.abs(amount - 1f) > 0.02f) {
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .clickable {
                                onDraggingChanged(false)
                                onAmountChange(1f)
                            }
                            .testTag("profile_amount_reset"),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.RestartAlt,
                            contentDescription = "Reset profile amount to 100%",
                            tint = StudioAmber,
                            modifier = Modifier.size(15.dp)
                        )
                    }
                }
            }
        }

        StudioPrecisionTrack(
            value = amount.coerceIn(0f, 2f),
            valueRange = 0f..2f,
            step = 0.01f,
            accentColor = StudioAmber,
            compact = true,
            testTag = "slider_profile_amount",
            onValueChange = {
                onDraggingChanged(true)
                onAmountChange(it)
            },
            onValueChangeFinished = { onDraggingChanged(false) },
            onReset = {
                onDraggingChanged(false)
                onAmountChange(1f)
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileMenuSheet(
    activeProfileId: String,
    profileAmount: Float,
    recentProfileIds: List<String>,
    favoriteProfileIds: Set<String>,
    onSelectProfile: (String) -> Unit,
    onAmountChange: (Float) -> Unit,
    onDraggingChanged: (Boolean) -> Unit,
    onToggleFavorite: (String) -> Unit,
    onOpenBrowser: () -> Unit,
    onDismiss: () -> Unit
) {
    val activeProfile = remember(activeProfileId) { CameraProfiles.byId(activeProfileId) }
    var expandedCategories by remember(activeProfileId) {
        mutableStateOf(setOf(activeProfile.category))
    }
    var isRecentExpanded by remember { mutableStateOf(true) }
    var isFavoritesExpanded by remember { mutableStateOf(true) }

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = StudioSurface,
        contentColor = StudioTextPrimary
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .heightIn(max = 580.dp)
                .padding(horizontal = 16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Camera Profiles",
                        style = MaterialTheme.typography.titleMedium,
                        color = StudioTextPrimary
                    )
                    Text(
                        text = "Non-destructive base layer • ${CameraProfiles.ALL.size} profiles",
                        style = MaterialTheme.typography.labelSmall,
                        color = StudioTextSecondary
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    IconButton(
                        onClick = {
                            onDismiss()
                            onOpenBrowser()
                        },
                        modifier = Modifier.testTag("profile_menu_open_browser")
                    ) {
                        Icon(
                            imageVector = Icons.Default.GridView,
                            contentDescription = stringResource(R.string.cd_open_profile_browser),
                            tint = StudioCyan
                        )
                    }
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.testTag("profile_menu_close")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close profile menu",
                            tint = StudioTextSecondary
                        )
                    }
                }
            }

            if (activeProfile.isCreative) {
                Spacer(modifier = Modifier.height(6.dp))
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = StudioSurfaceElevated,
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, StudioBorderSubtle, RoundedCornerShape(10.dp))
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    ProfileAmountRow(
                        amount = profileAmount,
                        onAmountChange = onAmountChange,
                        onDraggingChanged = onDraggingChanged
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))
            HorizontalDivider(color = StudioBorderSubtle, thickness = 1.dp)

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Recent section
                val recentProfiles = remember(recentProfileIds) {
                    recentProfileIds.map { CameraProfiles.byId(it) }.distinctBy { it.id }
                }
                if (recentProfiles.isNotEmpty()) {
                    ProfileExpandableGroup(
                        title = "Recent",
                        count = recentProfiles.size,
                        isExpanded = isRecentExpanded,
                        hasActiveItem = recentProfiles.any { it.id == activeProfileId },
                        leadingIcon = Icons.Default.History,
                        testTag = "profile_group_recent",
                        onToggle = { isRecentExpanded = !isRecentExpanded }
                    ) {
                        recentProfiles.forEach { profile ->
                            ProfileMenuRow(
                                profile = profile,
                                isSelected = profile.id == activeProfileId,
                                isFavorite = profile.id in favoriteProfileIds,
                                onSelect = {
                                    onSelectProfile(profile.id)
                                    onDismiss()
                                },
                                onToggleFavorite = { onToggleFavorite(profile.id) }
                            )
                        }
                    }
                }

                // Favorites section
                val favoriteProfiles = remember(favoriteProfileIds) {
                    CameraProfiles.ALL.filter { it.id in favoriteProfileIds }
                }
                ProfileExpandableGroup(
                    title = "Favorites",
                    count = favoriteProfiles.size,
                    isExpanded = isFavoritesExpanded,
                    hasActiveItem = favoriteProfiles.any { it.id == activeProfileId },
                    leadingIcon = Icons.Default.Star,
                    leadingTint = StudioAmber,
                    testTag = "profile_group_favorites",
                    onToggle = { isFavoritesExpanded = !isFavoritesExpanded }
                ) {
                    if (favoriteProfiles.isEmpty()) {
                        Text(
                            text = "Tap the star next to any profile to pin it here.",
                            style = MaterialTheme.typography.bodySmall,
                            color = StudioTextMuted,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)
                        )
                    } else {
                        favoriteProfiles.forEach { profile ->
                            ProfileMenuRow(
                                profile = profile,
                                isSelected = profile.id == activeProfileId,
                                isFavorite = true,
                                onSelect = {
                                    onSelectProfile(profile.id)
                                    onDismiss()
                                },
                                onToggleFavorite = { onToggleFavorite(profile.id) }
                            )
                        }
                    }
                }

                // 5 Expandable Profile Categories
                ProfileCategory.entries.forEach { category ->
                    val profiles = remember(category) { CameraProfiles.byCategory(category) }
                    val isExpanded = category in expandedCategories
                    ProfileExpandableGroup(
                        title = category.label,
                        count = profiles.size,
                        isExpanded = isExpanded,
                        hasActiveItem = activeProfile.category == category,
                        testTag = "profile_category_${category.name.lowercase()}",
                        onToggle = {
                            expandedCategories = if (isExpanded) {
                                expandedCategories - category
                            } else {
                                expandedCategories + category
                            }
                        }
                    ) {
                        profiles.forEach { profile ->
                            ProfileMenuRow(
                                profile = profile,
                                isSelected = profile.id == activeProfileId,
                                isFavorite = profile.id in favoriteProfileIds,
                                onSelect = {
                                    onSelectProfile(profile.id)
                                    onDismiss()
                                },
                                onToggleFavorite = { onToggleFavorite(profile.id) }
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))
            }
        }
    }
}

@Composable
private fun ProfileExpandableGroup(
    title: String,
    count: Int,
    isExpanded: Boolean,
    hasActiveItem: Boolean,
    testTag: String,
    onToggle: () -> Unit,
    leadingIcon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    leadingTint: Color = StudioCyan,
    content: @Composable () -> Unit
) {
    val arrowRotation by animateFloatAsState(
        targetValue = if (isExpanded) 90f else 0f,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "profile_group_arrow"
    )

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = StudioSurfaceElevated,
        modifier = Modifier
            .fillMaxWidth()
            .border(
                width = 1.dp,
                color = if (isExpanded) StudioBorder else StudioBorderSubtle,
                shape = RoundedCornerShape(12.dp)
            )
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = 48.dp)
                    .clickable(onClick = onToggle)
                    .padding(horizontal = 12.dp, vertical = 8.dp)
                    .testTag(testTag),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = if (isExpanded) "Collapse $title" else "Expand $title",
                        tint = if (isExpanded) StudioAmber else StudioTextSecondary,
                        modifier = Modifier
                            .size(18.dp)
                            .rotate(arrowRotation)
                    )
                    if (leadingIcon != null) {
                        Icon(
                            imageVector = leadingIcon,
                            contentDescription = null,
                            tint = leadingTint,
                            modifier = Modifier.size(15.dp)
                        )
                    }
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleSmall,
                        color = if (isExpanded || hasActiveItem) StudioTextPrimary else StudioTextSecondary
                    )
                    if (hasActiveItem) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(StudioAmber)
                        )
                    }
                }

                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = StudioSurfaceHighest
                ) {
                    Text(
                        text = count.toString(),
                        style = MaterialTheme.typography.labelSmall,
                        color = StudioTextSecondary,
                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp)
                    )
                }
            }

            AnimatedVisibility(
                visible = isExpanded,
                enter = expandVertically(animationSpec = spring(stiffness = Spring.StiffnessMediumLow)) + fadeIn(),
                exit = shrinkVertically(animationSpec = spring(stiffness = Spring.StiffnessMediumLow)) + fadeOut()
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    HorizontalDivider(color = StudioBorderSubtle, thickness = 1.dp)
                    content()
                }
            }
        }
    }
}

@Composable
private fun ProfileMenuRow(
    profile: CameraProfile,
    isSelected: Boolean,
    isFavorite: Boolean,
    onSelect: () -> Unit,
    onToggleFavorite: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 48.dp)
            .background(if (isSelected) StudioAmber.copy(alpha = 0.12f) else Color.Transparent)
            .clickable(onClick = onSelect)
            .padding(start = 14.dp, end = 4.dp, top = 4.dp, bottom = 4.dp)
            .testTag("profile_row_${profile.id}"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.weight(1f)
        ) {
            Box(
                modifier = Modifier
                    .size(14.dp)
                    .clip(CircleShape)
                    .background(Color(profile.swatchArgb))
                    .border(
                        width = 1.dp,
                        color = if (isSelected) StudioAmber else StudioBorder,
                        shape = CircleShape
                    )
            )
            Text(
                text = profile.name,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal
                ),
                color = if (isSelected) StudioAmber else StudioTextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            if (isSelected) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = "Active profile",
                    tint = StudioAmber,
                    modifier = Modifier
                        .padding(end = 4.dp)
                        .size(16.dp)
                )
            }
            IconButton(
                onClick = onToggleFavorite,
                modifier = Modifier.testTag("profile_star_${profile.id}")
            ) {
                Icon(
                    imageVector = if (isFavorite) Icons.Default.Star else Icons.Default.StarBorder,
                    contentDescription = if (isFavorite) {
                        "Remove ${profile.name} from favorites"
                    } else {
                        "Add ${profile.name} to favorites"
                    },
                    tint = if (isFavorite) StudioAmber else StudioTextMuted,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

private enum class BrowserFilter(val label: String) {
    ALL("All"),
    FAVORITES("Favorites"),
    BASIC("Basic"),
    FILM("Film"),
    CINEMATIC("Cinematic"),
    MUTED("Muted"),
    BW("B&W")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileBrowserSheet(
    activeProfileId: String,
    profileAmount: Float,
    favoriteProfileIds: Set<String>,
    thumbnails: Map<String, Bitmap>,
    isLoadingThumbnails: Boolean,
    onRequestThumbnails: () -> Unit,
    onSelectProfile: (String) -> Unit,
    onAmountChange: (Float) -> Unit,
    onDraggingChanged: (Boolean) -> Unit,
    onToggleFavorite: (String) -> Unit,
    onDismiss: () -> Unit
) {
    LaunchedEffect(Unit) {
        onRequestThumbnails()
    }

    val activeProfile = remember(activeProfileId) { CameraProfiles.byId(activeProfileId) }
    var activeFilter by remember { mutableStateOf(BrowserFilter.ALL) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = StudioSurface,
        contentColor = StudioTextPrimary
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .heightIn(max = 640.dp)
                .padding(horizontal = 16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Profile Browser",
                        style = MaterialTheme.typography.titleMedium,
                        color = StudioTextPrimary
                    )
                    Text(
                        text = "Active: ${activeProfile.name} • Tap ★ to favorite",
                        style = MaterialTheme.typography.labelSmall,
                        color = StudioTextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.testTag("profile_browser_close")
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close profile browser",
                        tint = StudioTextSecondary
                    )
                }
            }

            if (activeProfile.isCreative) {
                Spacer(modifier = Modifier.height(6.dp))
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = StudioSurfaceElevated,
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, StudioBorderSubtle, RoundedCornerShape(10.dp))
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    ProfileAmountRow(
                        amount = profileAmount,
                        onAmountChange = onAmountChange,
                        onDraggingChanged = onDraggingChanged
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                BrowserFilter.entries.forEach { filter ->
                    val selected = filter == activeFilter
                    val count = when (filter) {
                        BrowserFilter.ALL -> CameraProfiles.ALL.size
                        BrowserFilter.FAVORITES -> favoriteProfileIds.size
                        BrowserFilter.BASIC -> CameraProfiles.byCategory(ProfileCategory.BASIC).size
                        BrowserFilter.FILM -> CameraProfiles.byCategory(ProfileCategory.FILM).size
                        BrowserFilter.CINEMATIC -> CameraProfiles.byCategory(ProfileCategory.CINEMATIC).size
                        BrowserFilter.MUTED -> CameraProfiles.byCategory(ProfileCategory.MUTED).size
                        BrowserFilter.BW -> CameraProfiles.byCategory(ProfileCategory.BW).size
                    }
                    Box(
                        modifier = Modifier
                            .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                            .clip(RoundedCornerShape(20.dp))
                            .clickable { activeFilter = filter }
                            .testTag("profile_filter_${filter.name.lowercase()}"),
                        contentAlignment = Alignment.Center
                    ) {
                        Surface(
                            shape = RoundedCornerShape(20.dp),
                            color = if (selected) StudioAmber.copy(alpha = 0.2f) else StudioSurfaceElevated,
                            modifier = Modifier
                                .defaultMinSize(minHeight = 36.dp)
                                .border(
                                    width = 1.dp,
                                    color = if (selected) StudioAmber else StudioBorderSubtle,
                                    shape = RoundedCornerShape(20.dp)
                                )
                        ) {
                            Text(
                                text = if (filter == BrowserFilter.FAVORITES) "${filter.label} ($count)" else filter.label,
                                style = MaterialTheme.typography.labelMedium,
                                color = if (selected) StudioAmber else StudioTextSecondary,
                                maxLines = 1,
                                softWrap = false,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            if (isLoadingThumbnails && thumbnails.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            color = StudioCyan,
                            strokeWidth = 2.dp
                        )
                        Text(
                            text = "Rendering profile previews…",
                            style = MaterialTheme.typography.bodySmall,
                            color = StudioTextSecondary
                        )
                    }
                }
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    when (activeFilter) {
                        BrowserFilter.ALL -> {
                            ProfileCategory.entries.forEach { category ->
                                val list = remember(category) { CameraProfiles.byCategory(category) }
                                Text(
                                    text = category.label,
                                    style = MaterialTheme.typography.labelLarge,
                                    color = StudioTextSecondary,
                                    modifier = Modifier.padding(top = 2.dp)
                                )
                                ProfileGridRows(
                                    profiles = list,
                                    activeProfileId = activeProfileId,
                                    favoriteProfileIds = favoriteProfileIds,
                                    thumbnails = thumbnails,
                                    onSelectProfile = onSelectProfile,
                                    onToggleFavorite = onToggleFavorite
                                )
                            }
                        }

                        BrowserFilter.FAVORITES -> {
                            val favList = remember(favoriteProfileIds) {
                                CameraProfiles.ALL.filter { it.id in favoriteProfileIds }
                            }
                            if (favList.isEmpty()) {
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = StudioSurfaceElevated,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 12.dp)
                                ) {
                                    Text(
                                        text = "No favorite profiles yet. Tap ★ on any thumbnail to save it here.",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = StudioTextSecondary,
                                        modifier = Modifier.padding(16.dp)
                                    )
                                }
                            } else {
                                ProfileGridRows(
                                    profiles = favList,
                                    activeProfileId = activeProfileId,
                                    favoriteProfileIds = favoriteProfileIds,
                                    thumbnails = thumbnails,
                                    onSelectProfile = onSelectProfile,
                                    onToggleFavorite = onToggleFavorite
                                )
                            }
                        }

                        else -> {
                            val targetCat = when (activeFilter) {
                                BrowserFilter.BASIC -> ProfileCategory.BASIC
                                BrowserFilter.FILM -> ProfileCategory.FILM
                                BrowserFilter.CINEMATIC -> ProfileCategory.CINEMATIC
                                BrowserFilter.MUTED -> ProfileCategory.MUTED
                                BrowserFilter.BW -> ProfileCategory.BW
                                else -> ProfileCategory.BASIC
                            }
                            val list = remember(targetCat) { CameraProfiles.byCategory(targetCat) }
                            ProfileGridRows(
                                profiles = list,
                                activeProfileId = activeProfileId,
                                favoriteProfileIds = favoriteProfileIds,
                                thumbnails = thumbnails,
                                onSelectProfile = onSelectProfile,
                                onToggleFavorite = onToggleFavorite
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ProfileGridRows(
    profiles: List<CameraProfile>,
    activeProfileId: String,
    favoriteProfileIds: Set<String>,
    thumbnails: Map<String, Bitmap>,
    onSelectProfile: (String) -> Unit,
    onToggleFavorite: (String) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        profiles.chunked(3).forEach { rowItems ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                rowItems.forEach { profile ->
                    ProfileThumbnailCard(
                        profile = profile,
                        thumbnail = thumbnails[profile.id],
                        selected = profile.id == activeProfileId,
                        isFavorite = profile.id in favoriteProfileIds,
                        onSelect = { onSelectProfile(profile.id) },
                        onToggleFavorite = { onToggleFavorite(profile.id) },
                        modifier = Modifier.weight(1f)
                    )
                }
                repeat(3 - rowItems.size) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun ProfileThumbnailCard(
    profile: CameraProfile,
    thumbnail: Bitmap?,
    selected: Boolean,
    isFavorite: Boolean,
    onSelect: () -> Unit,
    onToggleFavorite: () -> Unit,
    modifier: Modifier = Modifier
) {
    val borderColor = if (selected) StudioAmber else StudioBorder

    Box(
        modifier = modifier
            .aspectRatio(0.88f)
            .clip(RoundedCornerShape(10.dp))
            .background(StudioSurfaceElevated)
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = borderColor,
                shape = RoundedCornerShape(10.dp)
            )
            .clickable(onClick = onSelect)
            .testTag("profile_card_${profile.id}")
    ) {
        if (thumbnail != null && !thumbnail.isRecycled) {
            Image(
                bitmap = thumbnail.asImageBitmap(),
                contentDescription = "${profile.name} profile preview",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.linearGradient(
                            colors = listOf(
                                Color(profile.swatchArgb).copy(alpha = 0.45f),
                                StudioSurfaceHighest
                            )
                        )
                    )
            )
        }

        // Top-right favorite star button (minimum 48dp touch area via padding)
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .size(48.dp)
                .clip(CircleShape)
                .clickable(onClick = onToggleFavorite)
                .testTag("profile_card_star_${profile.id}"),
            contentAlignment = Alignment.TopEnd
        ) {
            Box(
                modifier = Modifier
                    .padding(6.dp)
                    .size(26.dp)
                    .clip(CircleShape)
                    .background(StudioBackground.copy(alpha = 0.72f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (isFavorite) Icons.Default.Star else Icons.Default.StarBorder,
                    contentDescription = if (isFavorite) {
                        "Unfavorite ${profile.name}"
                    } else {
                        "Favorite ${profile.name}"
                    },
                    tint = if (isFavorite) StudioAmber else StudioTextPrimary,
                    modifier = Modifier.size(16.dp)
                )
            }
        }

        // Bottom gradient + label
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(Color.Transparent, Color(0xE00A0C12))
                    )
                )
                .padding(horizontal = 6.dp, vertical = 6.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(7.dp)
                        .clip(CircleShape)
                        .background(Color(profile.swatchArgb))
                )
                Text(
                    text = profile.name,
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
                    ),
                    color = if (selected) StudioAmber else StudioTextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
