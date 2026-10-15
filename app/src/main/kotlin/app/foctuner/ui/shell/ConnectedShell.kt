package app.foctuner.ui.shell

import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.foctuner.R
import app.foctuner.ui.components.Hairline
import app.foctuner.ui.components.PlainButton
import app.foctuner.ui.theme.FocTheme
import app.foctuner.ui.theme.Spacing
import app.foctuner.ui.theme.ThemePreviews

/**
 * Frame around the connected screens: what we're connected to at the top, destinations in a bottom bar on
 * phones and a rail on wide windows. The bottom bar steps aside while the keyboard is up.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ConnectedShell(
    header: SessionHeaderState,
    current: Destination,
    onNavigate: (Destination) -> Unit,
    onDisconnect: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable (Destination) -> Unit,
) {
    val colors = FocTheme.colors
    BoxWithConstraints(
        modifier
            .fillMaxSize()
            .background(colors.background),
    ) {
        val wide = maxWidth >= Spacing.wideBreakpoint
        val bottomBar = !wide && !WindowInsets.isImeVisible
        Column(Modifier.fillMaxSize()) {
            Header(header, onDisconnect)
            Row(Modifier.weight(1f)) {
                if (wide) {
                    Rail(current, onNavigate)
                    VerticalDivider(thickness = Dp.Hairline, color = colors.hairline)
                }
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .then(
                            if (bottomBar) {
                                Modifier
                            } else {
                                Modifier.windowInsetsPadding(
                                    WindowInsets.navigationBars.union(WindowInsets.ime).only(WindowInsetsSides.Bottom + WindowInsetsSides.End),
                                )
                            },
                        ),
                ) {
                    content(current)
                }
            }
            if (bottomBar) BottomBar(current, onNavigate)
        }
    }
}

@Composable
private fun Header(state: SessionHeaderState, onDisconnect: () -> Unit) {
    val colors = FocTheme.colors
    val liveLabel = stringResource(R.string.session_live)
    Column(
        Modifier
            .fillMaxWidth()
            .background(colors.background)
            .windowInsetsPadding(WindowInsets.statusBars.union(WindowInsets.displayCutout).only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = Spacing.row + Spacing.x1)
                .padding(start = Spacing.x2, end = Spacing.x1),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(Spacing.x1)
                    .background(if (state.live) colors.accent else colors.outline, CircleShape)
                    .semantics { if (state.live) contentDescription = liveLabel },
            )
            Spacer(Modifier.width(Spacing.x2 - Spacing.half))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        state.deviceName,
                        Modifier.weight(1f, fill = false),
                        color = colors.text,
                        style = FocTheme.type.bodyStrong,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    state.targetCanId?.let { id ->
                        Text(
                            "  →  " + stringResource(R.string.controllers_can_id, id),
                            color = colors.accent,
                            style = FocTheme.type.label,
                            maxLines = 1,
                        )
                    }
                }
                Text(
                    listOf(state.hardwareName, stringResource(R.string.session_firmware, state.firmware), state.linkLabel)
                        .filter { it.isNotEmpty() }
                        .joinToString(" · "),
                    color = colors.textTertiary,
                    style = FocTheme.type.caption,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            PlainButton(stringResource(R.string.session_disconnect), onDisconnect)
        }
        Hairline()
    }
}

@Composable
private fun BottomBar(current: Destination, onNavigate: (Destination) -> Unit) {
    val colors = FocTheme.colors
    Column(
        Modifier
            .fillMaxWidth()
            .background(colors.surface)
            .windowInsetsPadding(WindowInsets.navigationBars),
    ) {
        Hairline()
        Row(
            Modifier
                .fillMaxWidth()
                .height(64.dp)
                .selectableGroup(),
        ) {
            Destination.entries.forEach { destination ->
                NavItem(destination, destination == current, onNavigate, indicatorOnTop = true, Modifier.weight(1f).fillMaxHeight())
            }
        }
    }
}

@Composable
private fun Rail(current: Destination, onNavigate: (Destination) -> Unit) {
    Column(
        Modifier
            .width(88.dp)
            .fillMaxHeight()
            .background(FocTheme.colors.surface)
            .windowInsetsPadding(WindowInsets.navigationBars.only(WindowInsetsSides.Start + WindowInsetsSides.Bottom))
            .padding(top = Spacing.x1)
            .selectableGroup(),
        verticalArrangement = Arrangement.spacedBy(Spacing.half),
    ) {
        Destination.entries.forEach { destination ->
            NavItem(destination, destination == current, onNavigate, indicatorOnTop = false, Modifier.fillMaxWidth().height(72.dp))
        }
    }
}

@Composable
private fun NavItem(
    destination: Destination,
    selected: Boolean,
    onNavigate: (Destination) -> Unit,
    indicatorOnTop: Boolean,
    modifier: Modifier,
) {
    val colors = FocTheme.colors
    val tint = if (selected) colors.text else colors.textTertiary
    Box(
        modifier.selectable(selected = selected, role = Role.Tab, onClick = { onNavigate(destination) }),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) {
            Box(
                Modifier
                    .align(if (indicatorOnTop) Alignment.TopCenter else Alignment.CenterStart)
                    .size(if (indicatorOnTop) 32.dp else 2.dp, if (indicatorOnTop) 2.dp else 32.dp)
                    .background(colors.accent),
            )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Spacing.half)) {
            Icon(painterResource(destination.icon), contentDescription = null, tint = tint, modifier = Modifier.size(24.dp))
            Text(
                stringResource(destination.label),
                color = tint,
                style = FocTheme.type.caption.copy(fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal),
                maxLines = 1,
            )
        }
    }
}

@ThemePreviews
@Composable
private fun ShellPreview() {
    FocTheme {
        ConnectedShell(
            SessionHeaderState("Onewheel build", "60_MK6", "6.05", "Bluetooth", live = true, targetCanId = 25),
            Destination.Overview,
            {},
            {},
        ) {}
    }
}
