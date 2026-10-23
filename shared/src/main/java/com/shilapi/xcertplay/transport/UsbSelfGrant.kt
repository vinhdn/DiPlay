package com.shilapi.xcertplay.transport

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager

/**
 * Grants USB access to DiPlay itself when it holds MANAGE_USB, as a build signed with the head unit
 * ROM's platform key or installed as a privileged app does. Such a head unit then needs no
 * permission page, which some custom ROMs do not have.
 */
internal object UsbSelfGrant {
    internal const val MANAGE_USB = "android.permission.MANAGE_USB"

    fun available(context: Context): Boolean =
        context.checkSelfPermission(MANAGE_USB) == PackageManager.PERMISSION_GRANTED

    /** True when Android now reports the permission; false leaves the ordinary request to the caller. */
    @SuppressLint("PrivateApi", "DiscouragedPrivateApi")
    fun grant(context: Context, usbManager: UsbManager, device: UsbDevice): Boolean {
        if (!available(context)) return false
        return runCatching {
            // @SystemApi since Android 7: grants the device to the calling uid.
            UsbManager::class.java.getMethod("grantPermission", UsbDevice::class.java)
                .invoke(usbManager, device)
            usbManager.hasPermission(device)
        }.getOrDefault(false)
    }
}
