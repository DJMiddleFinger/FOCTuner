package app.foctuner.ui.connect

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.foctuner.R
import app.foctuner.ui.components.Hairline
import app.foctuner.ui.components.Notice
import app.foctuner.ui.components.NoticeTone
import app.foctuner.ui.components.OutlineButton
import app.foctuner.ui.components.PlainButton
import app.foctuner.ui.components.SectionHeader
import app.foctuner.ui.theme.FocTheme
import app.foctuner.ui.theme.Spacing
import app.foctuner.ui.theme.StressPreview
import app.foctuner.ui.theme.ThemePreviews

@Composable
fun ConnectScreen(
    state: ConnectState,
    onConnect: (DeviceEntry) -> Unit,
    onConnectDemo: () -> Unit,
    onConnectAddress: (String) -> Unit,
    onTogglePin: (DeviceEntry) -> Unit,
    onScan: () -> Unit,
    onEnableBluetooth: () -> Unit,
    onRequestPermission: () -> Unit,
    onDismissFailure: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val busy = state.connectingKey != null
    Box(
        modifier
            .fillMaxSize()
            .background(FocTheme.colors.background),
        contentAlignment = Alignment.TopCenter,
    ) {
        LazyColumn(
            Modifier
                .fillMaxSize()
                .widthIn(max = 640.dp),
            contentPadding = WindowInsets.safeDrawing.asPaddingValues(),
        ) {
            item {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 64.dp)
                        .padding(start = Spacing.x2, end = Spacing.x1),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(R.string.connect_title),
                        Modifier.weight(1f),
                        color = FocTheme.colors.text,
                        style = FocTheme.type.title,
                    )
                    PlainButton(
                        stringResource(if (state.scanning) R.string.connect_scanning else R.string.connect_scan),
                        onScan,
                        enabled = !state.scanning && !busy,
                    )
                }
            }
            state.failure?.let { message ->
                item {
                    Notice(
                        title = stringResource(R.string.connect_failed_title),
                        body = message,
                        tone = NoticeTone.Fault,
                        action = stringResource(R.string.connect_dismiss),
                        onAction = onDismissFailure,
                        modifier = Modifier.padding(vertical = Spacing.x1),
                    )
                }
            }
            if (state.recent.isNotEmpty()) {
                item { SectionHeader(stringResource(R.string.connect_section_recent)) }
                deviceRows(state.recent, state.connectingKey) { entry, connecting ->
                    DeviceRow(
                        title = entry.name,
                        detail = recentDetail(entry),
                        monoDetail = false,
                        onClick = { onConnect(entry) },
                        enabled = !busy,
                        connecting = connecting,
                        trailing = { PinToggle(entry.pinned) { onTogglePin(entry) } },
                    )
                }
            }

            item {
                SectionHeader(stringResource(R.string.connect_section_bluetooth))
                if (state.scanning) {
                    LinearProgressIndicator(
                        Modifier
                            .fillMaxWidth()
                            .height(2.dp),
                        color = FocTheme.colors.accent,
                        trackColor = FocTheme.colors.hairline,
                        gapSize = 0.dp,
                    )
                } else {
                    Hairline(Modifier.padding(top = 2.dp))
                }
            }
            bluetoothBody(state, onConnect, onScan, onEnableBluetooth, onRequestPermission)

            item { SectionHeader(stringResource(R.string.connect_section_usb)) }
            if (state.usb.isEmpty()) {
                item { Explanation(stringResource(R.string.connect_usb_none)) }
            } else {
                foundRows(state.usb, state.connectingKey, busy, onConnect)
            }

            item { SectionHeader(stringResource(R.string.connect_section_network)) }
            if (state.network.isEmpty()) {
                item { Explanation(stringResource(R.string.connect_network_none)) }
            } else {
                foundRows(state.network, state.connectingKey, busy, onConnect)
            }
            item { AddressEntry(enabled = !busy, onConnect = onConnectAddress) }

            item {
                SectionHeader(stringResource(R.string.connect_section_demo))
                DeviceRow(
                    title = stringResource(R.string.connect_demo_name),
                    detail = stringResource(R.string.connect_demo_detail),
                    monoDetail = false,
                    onClick = onConnectDemo,
                    enabled = !busy,
                    connecting = state.connectingKey == DEMO_KEY,
                )
                Spacer(Modifier.height(Spacing.x4))
            }
        }
    }
}

private fun LazyListScope.bluetoothBody(
    state: ConnectState,
    onConnect: (DeviceEntry) -> Unit,
    onScan: () -> Unit,
    onEnableBluetooth: () -> Unit,
    onRequestPermission: () -> Unit,
) {
    when (state.bluetoothStatus) {
        BluetoothStatus.Off -> item {
            Notice(
                title = stringResource(R.string.connect_bt_off_title),
                body = stringResource(R.string.connect_bt_off_body),
                action = stringResource(R.string.connect_bt_off_action),
                onAction = onEnableBluetooth,
            )
        }
        BluetoothStatus.NeedsPermission -> item {
            Notice(
                title = stringResource(R.string.connect_bt_permission_title),
                body = stringResource(R.string.connect_bt_permission_body),
                action = stringResource(R.string.connect_bt_permission_action),
                onAction = onRequestPermission,
            )
        }
        BluetoothStatus.NeedsLocationPermission -> item {
            Notice(
                title = stringResource(R.string.connect_bt_location_title),
                body = stringResource(R.string.connect_bt_location_body),
                action = stringResource(R.string.connect_bt_permission_action),
                onAction = onRequestPermission,
            )
        }
        BluetoothStatus.Unsupported -> item { Explanation(stringResource(R.string.connect_bt_unsupported)) }
        BluetoothStatus.Ready -> when {
            state.bluetooth.isNotEmpty() -> foundRows(state.bluetooth, state.connectingKey, state.connectingKey != null, onConnect)
            state.scanFinished && !state.scanning -> item {
                Notice(
                    title = stringResource(R.string.connect_bt_none_title),
                    checks = listOf(
                        stringResource(R.string.connect_bt_none_check_power),
                        stringResource(R.string.connect_bt_none_check_port),
                        stringResource(R.string.connect_bt_none_check_other),
                    ),
                    action = stringResource(R.string.connect_scan_again),
                    onAction = onScan,
                )
            }
            else -> Unit
        }
    }
}

private fun LazyListScope.foundRows(
    entries: List<DeviceEntry>,
    connectingKey: String?,
    busy: Boolean,
    onConnect: (DeviceEntry) -> Unit,
) = deviceRows(entries, connectingKey) { entry, connecting ->
    DeviceRow(
        title = entry.name,
        detail = entry.address,
        onClick = { onConnect(entry) },
        enabled = !busy,
        connecting = connecting,
        trailing = entry.rssi?.let { rssi -> { SignalStrength(rssi, Modifier.padding(end = Spacing.x2 - Spacing.half)) } },
    )
}

private fun LazyListScope.deviceRows(
    entries: List<DeviceEntry>,
    connectingKey: String?,
    row: @Composable (DeviceEntry, Boolean) -> Unit,
) {
    itemsIndexed(entries, key = { _, entry -> entry.link.name + entry.key }) { index, entry ->
        Column {
            row(entry, entry.key == connectingKey)
            if (index < entries.lastIndex) Hairline(Modifier.padding(start = Spacing.x2))
        }
    }
}

@Composable
private fun Explanation(text: String) {
    Text(
        text,
        Modifier.padding(horizontal = Spacing.x2, vertical = Spacing.x1),
        color = FocTheme.colors.textSecondary,
        style = FocTheme.type.supporting,
    )
}

@Composable
private fun AddressEntry(enabled: Boolean, onConnect: (String) -> Unit) {
    var open by rememberSaveable { mutableStateOf(false) }
    var address by rememberSaveable { mutableStateOf("") }
    if (!open) {
        Box(Modifier.padding(horizontal = Spacing.x1)) {
            PlainButton(stringResource(R.string.connect_by_address), { open = true }, enabled = enabled)
        }
        return
    }
    val submit = { if (address.isNotBlank()) onConnect(address.trim()) }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.x2, vertical = Spacing.x1),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.x1),
    ) {
        OutlinedTextField(
            value = address,
            onValueChange = { address = it },
            modifier = Modifier.weight(1f),
            enabled = enabled,
            singleLine = true,
            textStyle = FocTheme.type.value,
            label = { Text(stringResource(R.string.connect_address_label)) },
            placeholder = { Text(stringResource(R.string.connect_address_hint), style = FocTheme.type.value) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = { submit() }),
        )
        OutlineButton(stringResource(R.string.connect_address_go), submit, enabled = enabled && address.isNotBlank())
    }
}

private val previewState = ConnectState(
    recent = listOf(
        DeviceEntry("C4:2B:91:0E:7A:3D", "Onewheel build", "C4:2B:91:0E:7A:3D", LinkKind.Bluetooth, lastConnectedAt = System.currentTimeMillis() - 7_200_000, pinned = true),
        DeviceEntry("192.168.4.1:65102", "VESC Express", "192.168.4.1:65102", LinkKind.Network, lastConnectedAt = System.currentTimeMillis() - 86_400_000 * 3),
    ),
    bluetooth = listOf(
        DeviceEntry("E8:31:CD:44:02:9B", "VESC BLE UART", "E8:31:CD:44:02:9B", LinkKind.Bluetooth, rssi = -58),
        DeviceEntry("F0:08:D1:6C:11:20", "Trampa VESC 6 MkVI", "F0:08:D1:6C:11:20", LinkKind.Bluetooth, rssi = -81),
    ),
    scanning = true,
    connectingKey = "E8:31:CD:44:02:9B",
)

@ThemePreviews
@Composable
private fun ConnectPreview() {
    FocTheme {
        ConnectScreen(previewState, {}, {}, {}, {}, {}, {}, {}, {})
    }
}

@Preview(name = "Bluetooth off, light", widthDp = 400, heightDp = 860)
@Composable
private fun ConnectBluetoothOffPreview() {
    FocTheme(darkTheme = false) {
        ConnectScreen(ConnectState(bluetoothStatus = BluetoothStatus.Off), {}, {}, {}, {}, {}, {}, {}, {})
    }
}

@StressPreview
@Composable
private fun ConnectStressPreview() {
    FocTheme {
        ConnectScreen(
            ConnectState(
                recent = listOf(
                    DeviceEntry("a", "Rear hub motor controller, left side of the cargo bike", "C4:2B:91:0E:7A:3D", LinkKind.Bluetooth, lastConnectedAt = 0, pinned = true),
                ),
                scanFinished = true,
                failure = "The controller stopped answering after 5 s. Move closer, or power-cycle the BLE module.",
            ),
            {}, {}, {}, {}, {}, {}, {}, {},
        )
    }
}
