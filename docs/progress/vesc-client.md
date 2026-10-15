# Controller client and simulator (`core:vesc`, `core:sim`) progress

Owner: the vesc-client side session. Scope: `core/vesc/**`, `core/sim/**` and this file. No git state changes.

## Done

`./gradlew :core:vesc:test :core:sim:test`: 37 tests pass (21 in `core:vesc`: 4 existing reply parsers + 17 client; 16 in `core:sim`). No compiler warnings.

### `core:vesc`

Public API (package `app.foctuner.vesc`):

```kotlin
class VescClient(transport: Transport, scope: CoroutineScope, options: VescClientOptions = VescClientOptions()) : VescController
    val connected: StateFlow<Boolean>
    val prints: SharedFlow<String>                    // every COMM_PRINT line, terminal output included
    suspend fun pingCan(): List<Int>
    fun can(controllerId: Int): VescController        // 0..254, reached through the attached controller
    suspend fun close()                               // releases active motors, closes the transport

open class VescController                             // the attached controller, or one behind COMM_FORWARD_CAN
    val motor: MotorControl
    suspend fun firmwareInfo(): FirmwareInfo
    suspend fun values(): RealtimeValues
    fun realtime(period: Duration): Flow<RealtimeValues>
    suspend fun terminal(command: String): String

class MotorControl
    suspend fun setCurrent(amps: Float)  setBrakeCurrent(amps: Float)  setDuty(duty: Float)  setRpm(erpm: Int)  release()

VescException (IOException) > VescTimeoutException, VescLinkException
```

Internals: `PacketLink` (decoder loop with idle reset, framing, write splitting), `VescSession` (reply bookkeeping, keepalive, shutdown), `Requests` (payloads and scales), `VescClientOptions` (every timeout, with the reason for its default).

Behaviour worth knowing (all from `commands.c`, `terminal.c`, `timeout.c`, `comm_can.c`, `mcpwm_foc.c` in `vedderb/bldc` master and VESC Tool's `commands.cpp`, read 2026-10-15):

- **Replies to CAN-forwarded commands carry no sender.** The remote controller answers through the attached one with an ordinary packet of the same reply id. Requests that expect the same reply id are therefore serialized (one lock per reply id) and the first packet with that id after the request is its reply. A late reply to a timed-out request can be mistaken for the next one.
- **Terminal output has no end marker.** Each `commands_printf` is one COMM_PRINT; the first is the echo `-> cmd \n`. `terminal()` waits for the echo (`requestTimeout`), then for 250 ms of silence (`terminalQuietPeriod`) and returns the lines without the echo. `COMM_TERMINAL_CMD` and `COMM_PING_CAN` run in the firmware's single "blocking" thread and a command that arrives while it is busy is silently dropped, so the client serializes both behind one lock.
- **Release is `COMM_SET_CURRENT` 0.** The firmware releases a motor for any current below `cc_min_current` (0.05 A by default). `COMM_MOTOR_ESTOP` is a different thing (ignore all input for N ms) and is not exposed yet.
- **Keepalive** is `COMM_ALIVE` every 100 ms, only while a motor command is active, per controller (forwarded for CAN controllers). It never repeats the command, and `release()` stops it. The firmware command timeout is 1000 ms by default (0 disables it); VESC Tool instead sends alive packets for as long as it is connected, which is why we don't copy it.
- **Shutdown** (`close()` or cancelling the scope): the guardian coroutine starts undispatched so even an immediate cancel runs it, sends the release to every active controller, then closes the transport, each bounded by `shutdownTimeout`. A test mutation that started it normally failed `releasesTheMotorAndClosesTheLinkOnClose`.
- Partial frames are dropped after 250 ms idle (`frameIdleTimeout`); a stale prefix otherwise holds back the next short reply (test `aStalePartialFrameWouldOtherwiseSwallowTheNextShortReply`).
- `realtime(period)` is start-to-start fixed rate; a timed-out poll is skipped, a dropped link ends the flow with `VescLinkException`.

### `core:sim`

`SimulatedVesc(timeSource, chunkSize, hardwareName, controllerId, dualMotor, commandTimeout) : Transport`, entirely in memory, driven lazily by the `TimeSource` (pass `TestScope.testTimeSource` in tests, nothing in the app).

- Answers COMM_FW_VERSION (7.01, `60_MK6`, uuid `SIMULATEDVSC`, second motor last byte + 1), COMM_GET_VALUES (full mask, firmware field order and scales), COMM_TERMINAL_CMD (`help`, `mem`, `fault`, `faults`, `uptime`, `fw_info`, `hw_status`, invalid command; echo first, one COMM_PRINT per line), COMM_PING_CAN (lists `controllerId + 1` on a dual board), COMM_FORWARD_CAN (the second motor answers locally; any other id gets no reply), COMM_SET_DUTY / CURRENT / CURRENT_BRAKE / RPM, COMM_ALIVE. Everything else is ignored like unknown commands in the firmware.
- Command timeout as in `timeout.c`: both motors are released `commandTimeout` after the last command or alive (applied at the moment it expired, not when noticed), and `timedOut` shows in the values status byte until the next command.
- Toy model (`MotorModel.kt`): first-order current loop, rotor with drag and Coulomb friction, no-load speed from pack voltage, 12S pack that sags with input current and drains, FET and motor heating with different time constants, Ah/Wh in both directions, tachometer in 1/6 electrical revolutions.
- `chunkSize = 20` splits replies into BLE-sized pieces and makes `maxWriteSize` 20 so the client splits its writes.
- `isDriven(controllerId)` for tests and demo UIs.

## Next

1. `COMM_MOTOR_ESTOP` (`emergencyStop(ms)`): the UI's STOP button needs it (PARITY: stop disables commands for 5 s). Add to `VescController`, make the sim honour it (ignore input for N ms), test.
2. Decide with the lead whether the UI should get a dead-man lease on motor commands. Today a command lives until `release()`, `close()` or scope cancellation, so a UI that stalls (but keeps its process) keeps the motor alive. Cheapest fix: `MotorControl` takes an optional `holdFor` that the keepalive stops honouring after that long unless the command is refreshed.
3. Config transfer on `VescController` once `core:config` exposes its codec: `COMM_GET_MCCONF` / `COMM_SET_MCCONF` / `COMM_GET_APPCONF` / `COMM_SET_APPCONF` (replies are long, so check them against `PacketCodec` at the 512 byte firmware limit and the BLE path), plus signature mismatch handling. The sim then needs the matching handlers and a config store.
4. `COMM_GET_VALUES_SETUP` and the `_SELECTIVE` variants for the poll manager (PARITY section 1), and `COMM_GET_STATS`.
5. Long-running commands that go through the firmware's blocking thread (`COMM_DETECT_*`, 10+ s): they need their own timeouts and must share `blockingCommandLock`.
6. Sim: fault injection (over-temperature, DRV), position hold, more terminal commands, a settable pack, simulated reply latency for UI demos.

## Unverified

- Nothing has talked to a real controller. Scales and layouts come from the firmware source and VESC Tool, and the sim is written from the same source, so a mistake in my reading would be shared by both and tests would still pass. The first run on hardware should compare `values()` with VESC Tool's realtime page.
- Defaults chosen without hardware: `frameIdleTimeout` 250 ms (BLE gap argument, not measured), `keepaliveInterval` 100 ms (leaves 10x margin to the default 1 s timeout; a user timeout below ~300 ms would need a smaller interval), `terminalQuietPeriod` 250 ms (commands such as `foc_dc_cal` that pause longer will be cut short).
- CAN: behaviour on a real bus with several controllers (reply attribution, reply loss while the bus is busy) is reasoned from `comm_can.c`, not observed. `values()` on a CAN view does not check `controllerId` in the reply because firmware older than the field would report 0.
- `PacketCodec` frames over 512 bytes are accepted by us but not by firmware parsers; no request we build is near that.
- Cancelling a write in the middle of a multi-piece frame leaves a partial frame on the wire (documented on `PacketLink.send`); only matters for requests longer than `maxWriteSize` (terminal commands over BLE).
