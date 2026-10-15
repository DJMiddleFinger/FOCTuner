package app.foctuner.ui.connect

import android.text.format.DateUtils
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.foctuner.R
import app.foctuner.ui.theme.FocTheme
import app.foctuner.ui.theme.Spacing

@Composable
internal fun DeviceRow(
    title: String,
    detail: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    monoDetail: Boolean = true,
    enabled: Boolean = true,
    connecting: Boolean = false,
    trailing: (@Composable () -> Unit)? = null,
) {
    val colors = FocTheme.colors
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = Spacing.twoLineRow)
            .clickable(enabled = enabled && !connecting, role = Role.Button, onClick = onClick)
            .padding(start = Spacing.x2, end = if (trailing == null) Spacing.x2 else Spacing.half),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.x2),
    ) {
        Column(
            Modifier
                .weight(1f)
                .padding(vertical = Spacing.x1),
        ) {
            Text(
                title,
                color = if (enabled) colors.text else colors.textTertiary,
                style = FocTheme.type.bodyStrong,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                if (connecting) stringResource(R.string.connect_connecting) else detail,
                color = if (connecting) colors.accent else colors.textTertiary,
                style = if (monoDetail && !connecting) FocTheme.type.code else FocTheme.type.supporting,
                maxLines = if (monoDetail) 1 else 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
        when {
            connecting -> Box(Modifier.size(Spacing.touchTarget), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.size(18.dp), color = colors.accent, strokeWidth = 2.dp)
            }
            trailing != null -> trailing()
        }
    }
}

@Composable
internal fun SignalStrength(rssi: Int, modifier: Modifier = Modifier) {
    val colors = FocTheme.colors
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.x1)) {
        Text(
            stringResource(R.string.connect_signal, rssi).replace('-', '−'),
            color = colors.textTertiary,
            style = FocTheme.type.code,
        )
        val bars = when {
            rssi >= -60 -> 4
            rssi >= -70 -> 3
            rssi >= -80 -> 2
            else -> 1
        }
        Canvas(Modifier.size(width = 14.dp, height = 12.dp)) {
            val barWidth = 2.dp.toPx()
            val gap = (size.width - 4 * barWidth) / 3
            for (i in 0 until 4) {
                val h = size.height * (i + 1) / 4
                drawRoundRect(
                    color = if (i < bars) colors.textSecondary else colors.hairline,
                    topLeft = Offset(i * (barWidth + gap), size.height - h),
                    size = Size(barWidth, h),
                    cornerRadius = CornerRadius(barWidth / 2),
                )
            }
        }
    }
}

@Composable
internal fun PinToggle(pinned: Boolean, onToggle: () -> Unit) {
    IconButton(onClick = onToggle) {
        Icon(
            painterResource(if (pinned) R.drawable.ic_pin_filled else R.drawable.ic_pin),
            contentDescription = stringResource(if (pinned) R.string.connect_unpin else R.string.connect_pin),
            tint = if (pinned) FocTheme.colors.text else FocTheme.colors.textTertiary,
            modifier = Modifier.size(20.dp),
        )
    }
}

@Composable
internal fun recentDetail(entry: DeviceEntry): String {
    val link = stringResource(entry.link.label)
    val ago = entry.lastConnectedAt?.let {
        DateUtils.getRelativeTimeSpanString(it, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()
    }
    return listOfNotNull(link, ago).joinToString(" · ")
}

internal val LinkKind.label: Int
    get() = when (this) {
        LinkKind.Bluetooth -> R.string.connect_link_bluetooth
        LinkKind.Usb -> R.string.connect_link_usb
        LinkKind.Network -> R.string.connect_link_network
    }
