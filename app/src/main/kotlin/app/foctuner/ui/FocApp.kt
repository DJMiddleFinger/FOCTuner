package app.foctuner.ui

import android.bluetooth.BluetoothAdapter
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.foctuner.R
import app.foctuner.prefs.INTRO_VERSION
import app.foctuner.sim.SimulatedVesc
import app.foctuner.ui.about.AppPage
import app.foctuner.ui.about.AppPageScreen
import app.foctuner.ui.about.IntroScreen
import app.foctuner.ui.connect.ConnectScreen
import app.foctuner.ui.connect.ConnectViewModel
import app.foctuner.ui.connect.DEMO_KEY
import app.foctuner.ui.connect.LinkKind
import app.foctuner.ui.connect.label
import app.foctuner.ui.connect.toLinkKind
import app.foctuner.ui.controllers.ControllersScreen
import app.foctuner.ui.overview.OverviewScreen
import app.foctuner.ui.prefs.PreferencesViewModel
import app.foctuner.ui.session.SessionViewModel
import app.foctuner.ui.settings.SettingsScreen
import app.foctuner.ui.shell.ConnectedShell
import app.foctuner.ui.shell.Destination
import app.foctuner.ui.terminal.TerminalScreen
import app.foctuner.ui.tools.ToolsScreen
import app.foctuner.ui.theme.FocTheme

/** The first-run notice until it is accepted, then the connect screen until a controller answers, then the connected shell. */
@Composable
fun FocApp(
    connect: ConnectViewModel = viewModel(),
    session: SessionViewModel = viewModel(),
    preferences: PreferencesViewModel = viewModel(),
) {
    val prefs by preferences.state.collectAsStateWithLifecycle()
    val header by session.header.collectAsStateWithLifecycle()
    val loaded = prefs
    when {
        loaded == null -> Box(Modifier.fillMaxSize().background(FocTheme.colors.background))
        loaded.introAccepted < INTRO_VERSION -> {
            val activity = LocalActivity.current
            IntroScreen(onAccept = preferences::acceptIntro, onQuit = { activity?.finish() })
        }
        header == null -> ConnectRoute(connect, session)
        else -> {
            KeepScreenOn(loaded.keepScreenOn)
            ConnectedRoute(session)
        }
    }
}

@Composable
private fun KeepScreenOn(on: Boolean) {
    val view = LocalView.current
    DisposableEffect(view, on) {
        view.keepScreenOn = on
        onDispose { view.keepScreenOn = false }
    }
}

@Composable
private fun ConnectRoute(connect: ConnectViewModel, session: SessionViewModel) {
    var page by rememberSaveable { mutableStateOf<AppPage?>(null) }
    page?.let { shown ->
        AppPageScreen(shown, onBack = { page = null })
        return
    }
    val state by connect.state.collectAsStateWithLifecycle()
    val attempt by session.attempt.collectAsStateWithLifecycle()
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { connect.scan() }
    val enableBluetooth = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { connect.scan() }
    val demoName = stringResource(R.string.connect_demo_name)
    val demoLabel = stringResource(R.string.connect_link_demo)
    val linkLabels = mapOf(
        LinkKind.Bluetooth to stringResource(LinkKind.Bluetooth.label),
        LinkKind.Usb to stringResource(LinkKind.Usb.label),
        LinkKind.Network to stringResource(LinkKind.Network.label),
    )

    LaunchedEffect(Unit) { connect.scan() }

    ConnectScreen(
        state = state.copy(connectingKey = attempt.key, failure = attempt.failure),
        onConnect = { entry ->
            connect.device(entry)?.let { device ->
                session.connect(entry.key, device.name, linkLabels.getValue(entry.link), onConnected = { connect.remember(device) }) {
                    connect.open(device)
                }
            }
        },
        onConnectDemo = { session.connect(DEMO_KEY, demoName, demoLabel, demo = true) { SimulatedVesc() } },
        onConnectAddress = { address ->
            connect.deviceForAddress(address)?.let { device ->
                session.connect(device.key, device.name, linkLabels.getValue(device.kind.toLinkKind()), onConnected = { connect.remember(device) }) {
                    connect.open(device)
                }
            }
        },
        onTogglePin = connect::togglePin,
        onScan = connect::scan,
        onEnableBluetooth = { enableBluetooth.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)) },
        onRequestPermission = { permissions.launch(connect.missingBluetoothPermissions.toTypedArray()) },
        onDismissFailure = session::dismissFailure,
        onForget = connect::forget,
        onOpenPreferences = { page = AppPage.Preferences },
        onOpenAbout = { page = AppPage.About },
    )
}

@Composable
private fun ConnectedRoute(session: SessionViewModel) {
    val header by session.header.collectAsStateWithLifecycle()
    val overview by session.overview.collectAsStateWithLifecycle()
    val terminal by session.terminal.collectAsStateWithLifecycle()
    val controllers by session.controllers.collectAsStateWithLifecycle()
    val settings by session.settings.state.collectAsStateWithLifecycle()
    val tools by session.tools.collectAsStateWithLifecycle()
    var destination by rememberSaveable { mutableStateOf(Destination.Overview) }

    BackHandler {
        if (destination != Destination.Overview) destination = Destination.Overview else session.disconnect()
    }

    val current = header ?: return
    ConnectedShell(current, destination, { destination = it }, session::disconnect) { shown ->
        when (shown) {
            Destination.Overview -> overview?.let { OverviewScreen(it) }
                ?: Box(Modifier.fillMaxSize().background(FocTheme.colors.background))
            Destination.Settings -> SettingsScreen(settings, session.settings)
            Destination.Tools -> ToolsScreen(tools)
            Destination.Terminal -> TerminalScreen(terminal, session::onTerminalInput, session::sendCommand, session::clearTerminal)
            Destination.Controllers -> ControllersScreen(controllers, session::select, session::scanCan)
        }
    }
}
