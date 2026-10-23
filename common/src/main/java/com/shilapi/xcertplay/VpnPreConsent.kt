package com.shilapi.xcertplay

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.os.IBinder
import android.os.Process
import com.shilapi.xcertplay.adb.AdbKeys
import com.shilapi.xcertplay.adb.LocalAdb

/**
 * Some firmware ships no VPN consent dialog (Geely Galaxy E8, #604), so wired CarPlay could never
 * start. Android counts an allowed ACTIVATE_VPN app op as that consent, and the car's own adb can
 * set it. Only an adb key the driver already approved is used; this never asks for approval.
 */
internal object VpnPreConsent {
    internal const val CONTROL_VPN = "android.permission.CONTROL_VPN"
    private const val PER_USER_RANGE = 100_000
    private const val TYPE_VPN_SERVICE = 1

    fun grant(context: Context, adb: LocalAdb = LocalAdb(AdbKeys.load(context))): LocalAdb.Access = adb.use {
        val access = it.connect(mayAsk = false)
        if (access == LocalAdb.Access.READY) it.shell("appops set ${context.packageName} ACTIVATE_VPN allow")
        access
    }

    /**
     * A build signed with the head unit ROM's platform key holds CONTROL_VPN, so it records the
     * consent itself, as the system dialog's OK button does. True when the call went through;
     * Android 12 and later moved the call, and there this returns false.
     */
    @SuppressLint("PrivateApi", "DiscouragedPrivateApi")
    fun grantSelf(context: Context): Boolean {
        if (context.checkSelfPermission(CONTROL_VPN) != PackageManager.PERMISSION_GRANTED) return false
        return runCatching {
            val binder = Class.forName("android.os.ServiceManager")
                .getMethod("getService", String::class.java)
                .invoke(null, Context.CONNECTIVITY_SERVICE) as IBinder
            val service = Class.forName("android.net.IConnectivityManager\$Stub")
                .getMethod("asInterface", IBinder::class.java).invoke(null, binder)
            val userId = Process.myUid() / PER_USER_RANGE
            val authorize = service.javaClass.methods.first { it.name == "setVpnPackageAuthorization" }
            // Android 9 and 10 take a boolean; Android 11 takes the VPN type.
            val allow: Any = if (authorize.parameterTypes[2] == java.lang.Boolean.TYPE) true else TYPE_VPN_SERVICE
            authorize.invoke(service, context.packageName, userId, allow)
            true
        }.getOrDefault(false)
    }
}
