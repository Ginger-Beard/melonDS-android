package me.magnum.melonds.ir

import android.content.Intent
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.core.content.IntentCompat
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Invisible target of the system's USB_DEVICE_ATTACHED prompt for the IR dongle. Accepting the prompt
 * grants USB permission; this activity hands the device to [IrDongleManager] and finishes at once, so
 * a running game stays on screen (it only sees a pause/resume). If no game is running, the app is
 * brought to the front instead, as the prompt ("Open melonDS?") promises.
 */
@AndroidEntryPoint
class IrDongleAttachActivity : ComponentActivity() {

    @Inject
    lateinit var irDongleManager: IrDongleManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleIntent(intent)
        finish()
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.action != UsbManager.ACTION_USB_DEVICE_ATTACHED) return

        val device = IntentCompat.getParcelableExtra(intent, UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        irDongleManager.onDeviceAttachIntent(device)

        if (!irDongleManager.isEmulationSessionActive()) {
            packageManager.getLaunchIntentForPackage(packageName)?.let {
                startActivity(it)
            }
        }
    }
}
