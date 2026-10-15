package app.foctuner.link

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

private const val ACTION_USB_PERMISSION = "app.foctuner.USB_PERMISSION"

/**
 * Returns whether the app may talk to [device], showing the system dialog when it has not been granted yet.
 * The dialog belongs to the system and appears over whatever is on screen; the user's answer is the result.
 */
internal suspend fun UsbManager.requestPermission(context: Context, device: UsbDevice): Boolean {
    if (hasPermission(device)) return true
    return suspendCancellableCoroutine { continuation ->
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(receiverContext: Context, intent: Intent) {
                context.unregisterReceiver(this)
                continuation.resume(intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false))
            }
        }
        ContextCompat.registerReceiver(
            context, receiver, IntentFilter(ACTION_USB_PERMISSION), ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        continuation.invokeOnCancellation {
            try {
                context.unregisterReceiver(receiver)
            } catch (_: IllegalArgumentException) {
                // The answer arrived first and the receiver already removed itself.
            }
        }
        // The system fills in the granted extra, so the PendingIntent has to be mutable (Android 12+).
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
        val reply = Intent(ACTION_USB_PERMISSION).setPackage(context.packageName)
        requestPermission(device, PendingIntent.getBroadcast(context, 0, reply, flags))
    }
}
