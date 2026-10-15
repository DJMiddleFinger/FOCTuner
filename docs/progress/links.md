# Links (physical transports): progress

Owner paths: `core/transport/**`, `app/src/main/kotlin/app/foctuner/link/**`. `Transport` interface unchanged.

## Done

All four links plus discovery are implemented; `./gradlew :core:transport:test :app:assembleDebug` is green (13 JVM tests, all in `core/transport`; nothing in `link/` has run on a device).

| Piece | Where | Tested |
|---|---|---|
| TCP transport, default port 65102 | `core/transport/.../TcpTransport.kt` | JVM tests against a local `ServerSocket` |
| Blocking-stream transport shared by TCP and SPP | `.../StreamTransport.kt` | via the TCP tests |
| WiFi discovery: UDP beacon `name::ip::port` on port 65109 | `.../TcpBeacon.kt` | parser + loopback datagram test |
| Device model and "currently visible" aggregation with expiry | `.../LinkDevice.kt`, `.../VisibleDevices.kt` | virtual-time tests |
| BLE Nordic UART transport | `app/.../link/BleTransport.kt` | compiles only |
| BLE scan filtered to the VESC UART service | `app/.../link/BleScan.kt` | compiles only |
| USB CDC-ACM transport + permission request | `app/.../link/UsbTransport.kt`, `UsbPermission.kt` | compiles only |
| Bluetooth Classic SPP (RFCOMM) transport | `app/.../link/SppTransport.kt` | compiles only |
| Facade: permissions, scan, open | `app/.../link/Links.kt` | compiles only |

### Public API for the UI

```kotlin
val links = Links(applicationContext)

links.bluetoothEnabled                              // Boolean
links.missingPermissions(kinds = all)               // List<String>, feed to RequestMultiplePermissions
links.scan(kinds = all): Flow<List<LinkDevice>>     // devices around now; entries expire ~6 s after last sighting
links.open(device): Transport                       // suspend; throws IOException; close() to disconnect
LinkDevice.tcp(host, port = 65102, name = host)     // for a hand-typed address
```

`LinkDevice(kind: LinkKind, id, name, rssi: Int?)`, `LinkKind { BLE, USB, BLUETOOTH_CLASSIC, TCP }` live in `core:transport` (`app.foctuner.transport`). `scan` fails (the flow throws) if a requested link cannot scan, so request permissions and check `bluetoothEnabled` first, or pass only the kinds that are usable. Opening a USB device shows the system permission dialog and suspends until answered.

### What the VESC sources say (read 2026-10-15)

- BLE (`vesc_tool/bleuart.cpp`, `vesc_express/main/comm_ble.c`): service `6e400001-b5a3-f393-e0a9-e50e24dcca9e`; RX (app writes) `…0002`, write or write-without-response; TX (app subscribes) `…0003`, notify. VESC Tool writes without response in 20 byte chunks, never requests an MTU, writes `0100` to the TX CCCD, connects with the random address type and a 10 s timeout, does not reconnect. It scans for devices advertising the service (or in a saved preferred list). The Express advertises the service in both the advertisement and the scan response, with its configured name.
- TCP: VESC Tool's default port is 65102 (`vescinterface.cpp`, `TcpBox.qml`); a plain TCP client does no handshake and sets TCP_NODELAY. Express broadcasts `name::ip::65102\0` once a second to UDP 65109 (`comm_wifi.c`). The TCP *hub* (port 65101, `VESCTOOL:uuid:pass` login line) is a separate feature and not implemented here.
- USB: VESC hardware and VESC Express are CDC-ACM; VESC Tool opens them as serial ports.

## Next

1. **Hardware pass**, in this order (see Unverified): VESC Express over TCP and BLE, then USB cable to a VESC, then HC-05. Fix what the phone shows; the code is deliberately straightforward so fixes should be local.
2. The lead adds the USB attach manifest entries below (optional but gives persistent USB permission and auto-launch).
3. If the BLE hardware pass shows replies corrupted or missing after MTU negotiation, see the first Unverified item, then pass `requestedMtu = 23`, or cap `maxWriteSize` per product.
4. Before raising `targetSdk` to 37: Android 17 adds the `android.permission.ACCESS_LOCAL_NETWORK` runtime permission for LAN access (present in the API 37 `android.jar`). TCP and the UDP beacon would need it, via `Links.permissionsFor`.
5. Possible later additions, not needed for the scope: VESC Tool TCP hub client, remembering a preferred device (the UI session decides), a foreground service for a connection that must survive the app being backgrounded.

### Manifest entries wanted from the lead (not applied; I do not own the manifest)

In `<activity android:name=".MainActivity">` add:

```xml
<intent-filter>
    <action android:name="android.hardware.usb.action.USB_DEVICE_ATTACHED" />
</intent-filter>
<meta-data
    android:name="android.hardware.usb.action.USB_DEVICE_ATTACHED"
    android:resource="@xml/device_filter" />
```

and `app/src/main/res/xml/device_filter.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <usb-device vendor-id="1155" />  <!-- STMicroelectronics (0x0483): VESC hardware -->
    <usb-device vendor-id="12346" /> <!-- Espressif (0x303A): VESC Express native USB -->
</resources>
```

The vendor ids are from memory of how these enumerate and not confirmed; the transport itself matches on CDC-ACM structure, not VID/PID, so a wrong id only means no auto-launch. Whichever activity handles the intent should call `Links.open` for the attached device (`UsbManager.EXTRA_DEVICE`); with a matching filter the user can tick "always", which persists the permission.

## Unverified (needs a phone and a controller)

BLE
1. **MTU against VESC Express.** `BleTransport` requests MTU 247 (Android 14+ negotiates up to 517 by itself on connect). `comm_ble.c` then sets `ble_current_mtu = param->mtu.mtu` and sends notifications of that many bytes, while the ATT payload limit is MTU-3. If the ESP stack drops or truncates such notifications, replies would be lost or corrupt after negotiation, with no problem at 23. VESC Tool never requests an MTU. Check: connect to an Express, send `COMM_FW_VERSION` repeatedly, and confirm every reply arrives; a missing reply suggests this.
2. Write path: write-without-response with one chunk per `onCharacteristicWrite`. Check large writes (firmware upload, config write, 10+ KB) arrive intact and how fast; watch for the callback never arriving (a 5 s timeout then throws).
3. Notifications: the CCCD write completes and replies flow, on API 33+ (new callback signature) and on an older phone (legacy signatures). Both paths exist and have never executed.
4. Disconnects: walk out of range or power off the controller; the `received` flow must fail with an `IOException` and a pending `write` must throw.
5. `close()` from the app: the controller shows the BLE connection ended and a reconnect works immediately.
6. Encrypted mode (`BLE_MODE_ENCRYPTED` on the Express): the system pairing dialog should appear on the first access; check the connect then succeeds or fails cleanly.
7. Scan: the hardware filter on the service UUID finds the Express and the nRF/HM-10 style dongles (if some module only advertises the UUID in a form the filter misses, switch to a software filter on `scanRecord.serviceUuids`). Android refuses more than 5 scan starts in 30 s; the flow then fails with `BLE scan failed (error 6)`.
8. Android 11 and older: scanning needs ACCESS_FINE_LOCATION granted and the system location switch on; nothing detects the switch being off, the scan just finds nothing.
9. `requestConnectionPriority(HIGH)` helps or does nothing; battery impact is unmeasured.

USB
10. Permission dialog flow: `UsbManager.requestPermission` result arrives through the broadcast (including on Android 14+ with the mutable explicit PendingIntent), cancelling the coroutine during the dialog leaves no leaked receiver.
11. CDC detection finds the data interface and bulk endpoints on a real VESC (`COMM_FW_VERSION` round trip). `Links.scan` lists it with a sensible name (the product name is hidden until permission is granted, so the name is `USB serial (vid:pid)` first).
12. DTR via `SET_CONTROL_LINE_STATE` is harmless on ChibiOS firmware, and on VESC Express native USB (TinyUSB) it is what makes the device talk. Not verified either way.
13. Unplugging: the read loop treats a `bulkTransfer` result of -1 that returns in under half the 100 ms timeout as "device gone", and the flow then fails. Confirm this fires on unplug and that real idle timeouts do not trip it.
14. Throughput for large reads/writes (`bulkTransfer` is capped at 16 KB per call; `maxWriteSize` reports that).

Bluetooth Classic
15. HC-05 style module paired in system settings, connect via `Links.open`, round trip works. `Links.scan` shows only paired devices whose Bluetooth class is uncategorized or misc (how HC-05 reports); if a real module reports another class it will not be listed. `SppTransport.connect` blocks up to the system RFCOMM timeout (about 12 s) and cannot be cancelled before that.

WiFi
16. Beacon reception on a real phone: Android devices sometimes drop UDP broadcasts unless a `WifiManager.MulticastLock` is held (needs `CHANGE_WIFI_MULTICAST_STATE` in the manifest). If the list stays empty on a network where VESC Tool sees the Express, that is the cause.
17. TCP to an Express in AP mode (192.168.4.1:65102): Android may route traffic over mobile data when the WiFi has no internet; binding the socket to the WiFi network (`ConnectivityManager.bindProcessToNetwork`) may be needed.
