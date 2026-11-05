package app.foctuner.ui.tools.bridge

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.foctuner.R
import app.foctuner.transport.TcpBeacon
import app.foctuner.transport.TcpTransport
import app.foctuner.ui.components.Notice
import app.foctuner.ui.components.NoticeTone
import app.foctuner.ui.components.OutlineButton
import app.foctuner.ui.components.SectionHeader
import app.foctuner.ui.theme.FocTheme
import app.foctuner.ui.theme.Spacing
import app.foctuner.ui.tools.ToolContext
import app.foctuner.ui.tools.parts.ReadingRow
import app.foctuner.vesc.bridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket

enum class BridgeMode { Off, Server, Hub }

@Immutable
data class BridgeState(
    val mode: BridgeMode = BridgeMode.Off,
    /** This phone's IPv4 addresses, for typing into VESC Tool. */
    val addresses: List<String> = emptyList(),
    /** Set while a VESC Tool is connected to the server, or while registered with a hub. */
    val peer: String? = null,
    val packetsIn: Int = 0,
    val packetsOut: Int = 0,
    val failed: String? = null,
)

/**
 * Shares this connection with desktop VESC Tool (its TCP server, `TcpBox.qml`): a server on port 65102 that
 * relays packets to the controller and announces itself once a second on UDP 65109 so VESC Tool lists it; or a
 * registration with a TCP hub (`TcpHubBox.qml`) so VESC Tool reaches it from anywhere with the same id and
 * password. Runs while this connection is open and the app is running; the app doesn't keep it alive in the
 * background.
 */
class BridgeModel(private val context: ToolContext) {
    private val _state = MutableStateFlow(BridgeState(addresses = localAddresses()))
    val state: StateFlow<BridgeState> = _state.asStateFlow()
    private var job: Job? = null

    fun startServer(port: Int) = start(BridgeMode.Server) {
        val server = withContext(Dispatchers.IO) { ServerSocket(port) }
        val beacon = context.scope.launch {
            while (isActive) {
                localAddresses().firstOrNull()?.let { address ->
                    runCatching { TcpBeacon.broadcast(TcpBeacon.format(context.firmware.hardwareName, address, port)) }
                }
                delay(BEACON_MS)
            }
        }
        try {
            while (isActive) {
                val peer = TcpTransport.accept(server)
                _state.update { it.copy(peer = PEER_CONNECTED, failed = null) }
                relay(peer)
                _state.update { it.copy(peer = null) }
            }
        } finally {
            beacon.cancel()
            withContext(Dispatchers.IO) { server.close() }
        }
    }

    fun startHub(host: String, port: Int, id: String, password: String) = start(BridgeMode.Hub) {
        val peer = TcpTransport.registerWithHub(host.trim(), id, password, port)
        _state.update { it.copy(peer = PEER_CONNECTED) }
        relay(peer)
        throw IOException("The hub closed the connection")
    }

    fun stop() {
        job?.cancel()
        job = null
        _state.update { it.copy(mode = BridgeMode.Off, peer = null) }
    }

    private suspend fun relay(peer: TcpTransport) {
        context.client.bridge(peer) { fromPeer ->
            _state.update { if (fromPeer) it.copy(packetsIn = it.packetsIn + 1) else it.copy(packetsOut = it.packetsOut + 1) }
        }
    }

    private fun start(mode: BridgeMode, block: suspend kotlinx.coroutines.CoroutineScope.() -> Unit) {
        if (job?.isActive == true) return
        _state.update { it.copy(mode = mode, peer = null, failed = null, packetsIn = 0, packetsOut = 0, addresses = localAddresses()) }
        job = context.scope.launch {
            try {
                block()
            } catch (e: IOException) {
                _state.update { it.copy(mode = BridgeMode.Off, peer = null, failed = e.message ?: e.javaClass.simpleName) }
            } catch (e: IllegalArgumentException) {
                _state.update { it.copy(mode = BridgeMode.Off, peer = null, failed = e.message) }
            }
        }
    }

    private companion object {
        const val BEACON_MS = 1000L
        const val PEER_CONNECTED = "connected"

        fun localAddresses(): List<String> = runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList() }
                .filterIsInstance<Inet4Address>()
                .map { it.hostAddress.orEmpty() }
                .filter { it.isNotEmpty() }
        }.getOrDefault(emptyList())
    }
}

@Composable
fun BridgeTool(context: ToolContext) {
    val model = context.model { BridgeModel(context) }
    val state by model.state.collectAsStateWithLifecycle()
    val prefs = LocalContext.current.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    var port by rememberSaveable { mutableStateOf(prefs.getInt(KEY_PORT, TcpTransport.DEFAULT_PORT).toString()) }
    var hubHost by rememberSaveable { mutableStateOf(prefs.getString(KEY_HUB_HOST, DEFAULT_HUB) ?: DEFAULT_HUB) }
    var hubPort by rememberSaveable { mutableStateOf(prefs.getInt(KEY_HUB_PORT, TcpTransport.HUB_PORT).toString()) }
    var hubId by rememberSaveable { mutableStateOf(prefs.getString(KEY_HUB_ID, "") ?: "") }
    var hubPassword by rememberSaveable { mutableStateOf("") }
    val off = state.mode == BridgeMode.Off
    val portNumber = port.toIntOrNull()?.takeIf { it in 1024..65535 }
    val hubPortNumber = hubPort.toIntOrNull()?.takeIf { it in 1..65535 }
    val field = Modifier.fillMaxWidth().padding(horizontal = Spacing.x2)

    Column(Modifier.verticalScroll(rememberScrollState())) {
        if (context.demo) Notice(stringResource(R.string.bridge_demo), body = stringResource(R.string.bridge_demo_body))
        Text(
            stringResource(R.string.bridge_intro),
            Modifier.padding(horizontal = Spacing.x2, vertical = Spacing.x1),
            style = FocTheme.type.supporting,
            color = FocTheme.colors.textSecondary,
        )
        if (!off) {
            SectionHeader(stringResource(if (state.mode == BridgeMode.Server) R.string.bridge_server_running else R.string.bridge_hub_running))
            if (state.mode == BridgeMode.Server) {
                ReadingRow(stringResource(R.string.bridge_address), state.addresses.joinToString("\n").ifEmpty { stringResource(R.string.bridge_no_network) })
                ReadingRow(stringResource(R.string.bridge_port), port)
            }
            ReadingRow(
                stringResource(R.string.bridge_peer),
                stringResource(if (state.peer != null) R.string.bridge_peer_connected else R.string.bridge_peer_waiting),
            )
            ReadingRow(stringResource(R.string.bridge_packets_in), state.packetsIn.toString())
            ReadingRow(stringResource(R.string.bridge_packets_out), state.packetsOut.toString())
            OutlineButton(stringResource(R.string.bridge_stop), model::stop, Modifier.padding(Spacing.x2))
        }

        SectionHeader(stringResource(R.string.bridge_server_section))
        Text(
            stringResource(R.string.bridge_server_intro),
            Modifier.padding(horizontal = Spacing.x2, vertical = Spacing.x1),
            style = FocTheme.type.supporting,
            color = FocTheme.colors.textSecondary,
        )
        OutlinedTextField(
            port, { port = it.filter(Char::isDigit).take(5) }, field,
            enabled = off, singleLine = true, isError = portNumber == null,
            label = { Text(stringResource(R.string.bridge_port)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        )
        OutlineButton(
            stringResource(R.string.bridge_server_start),
            {
                val p = portNumber ?: return@OutlineButton
                prefs.edit().putInt(KEY_PORT, p).apply()
                model.startServer(p)
            },
            Modifier.padding(Spacing.x2),
            enabled = off && portNumber != null,
        )

        SectionHeader(stringResource(R.string.bridge_hub_section))
        Text(
            stringResource(R.string.bridge_hub_intro),
            Modifier.padding(horizontal = Spacing.x2, vertical = Spacing.x1),
            style = FocTheme.type.supporting,
            color = FocTheme.colors.textSecondary,
        )
        OutlinedTextField(hubHost, { hubHost = it.trim() }, field, enabled = off, singleLine = true, label = { Text(stringResource(R.string.bridge_hub_host)) })
        OutlinedTextField(
            hubPort, { hubPort = it.filter(Char::isDigit).take(5) }, field,
            enabled = off, singleLine = true, isError = hubPortNumber == null,
            label = { Text(stringResource(R.string.bridge_port)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        )
        OutlinedTextField(
            hubId, { hubId = it.filterNot { c -> c == ':' || c == '\n' } }, field,
            enabled = off, singleLine = true, isError = hubId.isNotEmpty() && hubId.trim().length < 3,
            label = { Text(stringResource(R.string.bridge_hub_id)) },
            supportingText = { Text(stringResource(R.string.bridge_hub_id_hint)) },
        )
        OutlinedTextField(
            hubPassword, { hubPassword = it.filterNot { c -> c == ':' || c == '\n' } }, field,
            enabled = off, singleLine = true,
            label = { Text(stringResource(R.string.bridge_hub_password)) },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        )
        Notice(stringResource(R.string.bridge_hub_cleartext), body = stringResource(R.string.bridge_hub_cleartext_body), tone = NoticeTone.Warning)
        OutlineButton(
            stringResource(R.string.bridge_hub_start),
            {
                val p = hubPortNumber ?: return@OutlineButton
                prefs.edit().putString(KEY_HUB_HOST, hubHost).putInt(KEY_HUB_PORT, p).putString(KEY_HUB_ID, hubId.trim()).apply()
                model.startHub(hubHost, p, hubId, hubPassword)
            },
            Modifier.padding(Spacing.x2),
            enabled = off && hubPortNumber != null && hubHost.isNotBlank() && hubId.trim().length >= 3,
        )
        state.failed?.let { Notice(stringResource(R.string.bridge_failed), body = it, tone = NoticeTone.Fault) }
    }
}

private const val PREFS = "bridge"
private const val KEY_PORT = "port"
private const val KEY_HUB_HOST = "hub_host"
private const val KEY_HUB_PORT = "hub_port"
private const val KEY_HUB_ID = "hub_id"
private const val DEFAULT_HUB = "veschub.vedder.se"
