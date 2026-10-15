package app.foctuner.ui.controllers

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.foctuner.R
import app.foctuner.ui.components.Hairline
import app.foctuner.ui.components.PlainButton
import app.foctuner.ui.components.SectionHeader
import app.foctuner.ui.theme.FocTheme
import app.foctuner.ui.theme.Spacing
import app.foctuner.ui.theme.StressPreview
import app.foctuner.ui.theme.ThemePreviews

@Composable
fun ControllersScreen(
    state: ControllersState,
    onSelect: (CanDevice) -> Unit,
    onScan: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = FocTheme.colors
    Box(
        modifier
            .fillMaxSize()
            .background(colors.background),
        contentAlignment = Alignment.TopCenter,
    ) {
        LazyColumn(
            Modifier
                .fillMaxSize()
                .widthIn(max = 640.dp)
                .selectableGroup(),
        ) {
            item {
                Text(
                    stringResource(R.string.controllers_explainer),
                    Modifier.padding(start = Spacing.x2, end = Spacing.x2, top = Spacing.x2),
                    color = colors.textSecondary,
                    style = FocTheme.type.supporting,
                )
                SectionHeader(stringResource(R.string.controllers_section)) {
                    PlainButton(stringResource(R.string.controllers_scan), onScan, enabled = !state.scanning)
                }
                if (state.scanning) {
                    LinearProgressIndicator(
                        Modifier
                            .fillMaxWidth()
                            .height(2.dp),
                        color = colors.accent,
                        trackColor = colors.hairline,
                        gapSize = 0.dp,
                    )
                } else {
                    Hairline(Modifier.padding(top = 2.dp))
                }
            }
            itemsIndexed(state.devices, key = { _, device -> device.canId }) { index, device ->
                Column {
                    ControllerRow(device, selected = device.canId == state.selectedCanId) { onSelect(device) }
                    if (index < state.devices.lastIndex) Hairline(Modifier.padding(start = Spacing.x2))
                }
            }
            item {
                val note = when {
                    state.scanning -> stringResource(R.string.controllers_scanning)
                    state.scanned && state.devices.size <= 1 -> stringResource(R.string.controllers_only_local)
                    else -> null
                }
                if (note != null) {
                    Hairline()
                    Text(
                        note,
                        Modifier.padding(Spacing.x2),
                        color = colors.textSecondary,
                        style = FocTheme.type.supporting,
                    )
                }
            }
        }
    }
}

@Composable
private fun ControllerRow(device: CanDevice, selected: Boolean, onClick: () -> Unit) {
    val colors = FocTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .heightIn(min = Spacing.twoLineRow)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .background(if (selected) colors.surface else Color.Transparent),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .width(3.dp)
                .fillMaxHeight()
                .background(if (selected) colors.accent else Color.Transparent),
        )
        Text(
            device.canId.toString(),
            Modifier
                .width(Spacing.x6 + Spacing.x1)
                .padding(start = Spacing.x2 - 3.dp),
            color = if (selected) colors.accent else colors.textSecondary,
            style = FocTheme.type.readout.copy(fontSize = FocTheme.type.title.fontSize),
        )
        Column(
            Modifier
                .weight(1f)
                .padding(vertical = Spacing.x1),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(device.hardwareName, color = colors.text, style = FocTheme.type.bodyStrong, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val detail = listOfNotNull(
                stringResource(R.string.session_firmware, device.firmware),
                device.firmwareName.ifEmpty { null },
                if (device.local) stringResource(R.string.controllers_local) else null,
            ).joinToString(" · ")
            Text(detail, color = colors.textTertiary, style = FocTheme.type.supporting, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Text(
            if (selected) stringResource(R.string.controllers_selected) else "",
            Modifier.padding(horizontal = Spacing.x2),
            color = colors.accent,
            style = FocTheme.type.caption,
        )
    }
}

private val previewDevices = listOf(
    CanDevice(canId = 12, local = true, hardwareName = "60_MK6", firmware = "6.05", firmwareName = "Refloat"),
    CanDevice(canId = 25, local = false, hardwareName = "60_MK6", firmware = "6.05"),
    CanDevice(canId = 110, local = false, hardwareName = "VESC BMS", firmware = "6.05"),
)

@ThemePreviews
@Composable
private fun ControllersPreview() {
    FocTheme { ControllersScreen(ControllersState(previewDevices, selectedCanId = 25, scanned = true), {}, {}) }
}

@StressPreview
@Composable
private fun ControllersStressPreview() {
    FocTheme {
        ControllersScreen(
            ControllersState(
                listOf(CanDevice(7, true, "Trampa VESC 100/250 with an unusually long hardware name", "6.06", "Custom build 2026-10-01")),
                selectedCanId = 7,
                scanned = true,
            ),
            {}, {},
        )
    }
}
