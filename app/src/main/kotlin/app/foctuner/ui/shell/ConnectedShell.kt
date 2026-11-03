package app.foctuner.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import app.foctuner.R
import app.foctuner.ui.components.Hairline
import app.foctuner.ui.components.Notice
import app.foctuner.ui.components.NoticeTone
import app.foctuner.ui.components.PageHeader
import app.foctuner.ui.components.PlainButton
import app.foctuner.ui.components.TabStrip
import app.foctuner.ui.session.UpdateStage
import app.foctuner.ui.theme.FocAnimatedContent
import app.foctuner.ui.theme.FocTheme
import app.foctuner.ui.theme.Spacing
import app.foctuner.ui.theme.ThemePreviews
import app.foctuner.ui.theme.lateral
import app.foctuner.ui.theme.push

/**
 * Frame around the connected screens, laid out like VESC Tool's mobile app: what we're connected to with a CAN
 * button and a menu (Display, Preferences, About, Disconnect), then the tabs along the top. Pages opened from the header replace the tabs with a back bar.
 */
@Composable
fun ConnectedShell(
    header: SessionHeaderState,
    current: Destination,
    onNavigate: (Destination) -> Unit,
    onBack: () -> Unit,
    onDisconnect: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable (Destination) -> Unit,
) {
    val colors = FocTheme.colors
    val tabs = Destination.tabs
    Column(
        modifier
            .fillMaxSize()
            .background(colors.background),
    ) {
        Header(
            state = header,
            onCan = { onNavigate(Destination.Controllers) },
            onMenu = onNavigate,
            onDisconnect = onDisconnect,
        )
        if (current.tab) {
            TabStrip(
                labels = tabs.map { stringResource(it.label) },
                selected = tabs.indexOf(current),
                onSelect = { if (header.updating == null) onNavigate(tabs[it]) },
                scrollable = true,
            )
        } else {
            PageHeader(stringResource(current.label), onBack)
            Hairline()
        }
        header.updating?.let { stage ->
            Notice(
                stringResource(R.string.fw_locked_title),
                body = stringResource(if (stage == UpdateStage.Installing) R.string.fw_locked_installing else R.string.fw_locked_uploading),
                tone = NoticeTone.Warning,
            )
        }
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime).only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal)),
        ) {
            FocAnimatedContent(
                target = current,
                label = "screen",
                modifier = Modifier.fillMaxSize(),
                transition = {
                    if (initialState.tab && targetState.tab) {
                        lateral(if (tabs.indexOf(targetState) >= tabs.indexOf(initialState)) 1 else -1)
                    } else {
                        push(forward = !targetState.tab)
                    }
                },
            ) { shown ->
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(colors.background),
                ) { content(shown) }
            }
        }
    }
}

@Composable
private fun Header(state: SessionHeaderState, onCan: () -> Unit, onMenu: (Destination) -> Unit, onDisconnect: () -> Unit) {
    val colors = FocTheme.colors
    val liveLabel = stringResource(R.string.session_live)
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .background(colors.background)
            .windowInsetsPadding(WindowInsets.statusBars.union(WindowInsets.displayCutout).only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
            .heightIn(min = Spacing.row + Spacing.x1)
            .padding(start = Spacing.x2),
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
            Text(
                state.deviceName,
                color = colors.text,
                style = FocTheme.type.bodyStrong,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
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
        PlainButton(
            state.targetCanId?.let { stringResource(R.string.session_can_target, it) } ?: stringResource(R.string.session_can),
            onCan,
            enabled = state.updating == null,
        )
        Box {
            IconButton(onClick = { menu = true }) {
                Icon(painterResource(R.drawable.ic_more), stringResource(R.string.session_menu), tint = colors.textSecondary)
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, containerColor = colors.surface) {
                for (page in listOf(Destination.Display, Destination.Preferences, Destination.About)) {
                    DropdownMenuItem(
                        text = { Text(stringResource(page.label), color = colors.text, style = FocTheme.type.body) },
                        onClick = {
                            menu = false
                            onMenu(page)
                        },
                    )
                }
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.session_disconnect), color = if (state.updating == null) colors.text else colors.textTertiary, style = FocTheme.type.body) },
                    enabled = state.updating == null,
                    onClick = {
                        menu = false
                        onDisconnect()
                    },
                )
            }
        }
    }
}

@ThemePreviews
@Composable
private fun ShellPreview() {
    FocTheme {
        ConnectedShell(
            SessionHeaderState("Onewheel build", "60_MK6", "6.05", "Bluetooth", live = true, targetCanId = 25),
            Destination.Start,
            {},
            {},
            {},
        ) {}
    }
}
