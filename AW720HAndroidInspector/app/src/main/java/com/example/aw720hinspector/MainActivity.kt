package com.example.aw720hinspector

import android.app.Activity
import android.app.PendingIntent
import android.content.*
import android.hardware.usb.*
import android.os.Bundle
import android.widget.*
import java.util.Locale

class MainActivity : Activity() {
    private lateinit var usbManager: UsbManager
    private lateinit var output: TextView
    private val actionUsbPermission = "com.example.aw720hinspector.USB_PERMISSION"

    private val permissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == actionUsbPermission) {
                val device = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
                val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                if (device != null && granted) inspect(device)
                else append("USB permission was not granted.")
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        usbManager = getSystemService(USB_SERVICE) as UsbManager
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 40, 32, 24)
            setBackgroundColor(0xFF0B1020.toInt())
        }
        val title = TextView(this).apply {
            text = "Alienware AW720H Inspector"
            textSize = 23f
            setTextColor(0xFFFFFFFF.toInt())
        }
        val info = TextView(this).apply {
            text = "Connect the AW720H 2.4 GHz dongle by USB OTG, then scan. This diagnostic reads USB descriptors only; it does not send lighting commands."
            textSize = 15f
            setTextColor(0xFFB9C7DD.toInt())
            setPadding(0, 18, 0, 18)
        }
        val scan = Button(this).apply {
            text = "Scan for AW720H dongle"
            setOnClickListener { scanDevices() }
        }
        output = TextView(this).apply {
            textSize = 12f
            typeface = android.graphics.Typeface.MONOSPACE
            setTextColor(0xFFD7E5FF.toInt())
            setPadding(0, 22, 0, 0)
            text = "Waiting for device…"
        }
        layout.addView(title)
        layout.addView(info)
        layout.addView(scan)
        layout.addView(output)
        val scroll = ScrollView(this)
        scroll.addView(layout)
        setContentView(scroll)
        registerReceiver(permissionReceiver, IntentFilter(actionUsbPermission), RECEIVER_NOT_EXPORTED)
    }

    private fun scanDevices() {
        val devices = usbManager.deviceList.values
        if (devices.isEmpty()) {
            output.text = "No USB devices found. Check OTG adapter and reconnect the dongle."
            return
        }
        val target = devices.firstOrNull { it.vendorId == 0x413C && it.productId == 0xA518 }
        if (target == null) {
            output.text = devices.joinToString("\n\n") {
                "${it.deviceName}\nVID=${hex(it.vendorId)} PID=${hex(it.productId)}"
            } + "\n\nAW720H (413C:A518) not found."
            return
        }
        output.text = "Found ${target.deviceName}\nVID=${hex(target.vendorId)} PID=${hex(target.productId)}\nRequesting USB permission…"
        if (usbManager.hasPermission(target)) inspect(target) else {
            val intent = PendingIntent.getBroadcast(
                this, 0, Intent(actionUsbPermission).setPackage(packageName),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            )
            usbManager.requestPermission(target, intent)
        }
    }

    private fun inspect(device: UsbDevice) {
        val report = StringBuilder()
        report.appendLine("DEVICE: ${device.deviceName}")
        report.appendLine("VID: ${hex(device.vendorId)}  PID: ${hex(device.productId)}")
        report.appendLine("INTERFACES: ${device.interfaceCount}")
        for (i in 0 until device.interfaceCount) {
            val intf = device.getInterface(i)
            report.appendLine("\nInterface ${intf.id}: class=${hex(intf.interfaceClass)} subclass=${hex(intf.interfaceSubclass)} protocol=${hex(intf.interfaceProtocol)}")
            report.appendLine("Endpoints: ${intf.endpointCount}")
            for (e in 0 until intf.endpointCount) {
                val ep = intf.getEndpoint(e)
                report.appendLine("  ep=${ep.endpointNumber} dir=${if (ep.direction == UsbConstants.USB_DIR_IN) "IN" else "OUT"} type=${ep.type} maxPacket=${ep.maxPacketSize}")
            }
        }
        val hid = (0 until device.interfaceCount).map { device.getInterface(it) }
            .firstOrNull { it.id == 3 && it.interfaceClass == UsbConstants.USB_CLASS_HID }
        if (hid == null) {
            report.appendLine("\nHID interface 3 not found.")
            output.text = report.toString()
            return
        }
        val connection = usbManager.openDevice(device)
        if (connection == null) {
            report.appendLine("\nCould not open USB device.")
            output.text = report.toString()
            return
        }
        try {
            val claimed = connection.claimInterface(hid, true)
            report.appendLine("\nHID interface 3 claim: $claimed")
            if (claimed) {
                // Standard GET_DESCRIPTOR (Report Descriptor, type 0x22).
                // IN | STANDARD | INTERFACE = 0x81. No output/feature reports are sent.
                val buffer = ByteArray(1024)
                val length = connection.controlTransfer(0x81, 0x06, 0x2200, 3, buffer, buffer.size, 3000)
                report.appendLine("HID report descriptor transfer length: $length")
                if (length > 0) {
                    report.appendLine("Descriptor hex:")
                    report.appendLine(buffer.take(length).joinToString(" ") { "%02x".format(it) })
                } else {
                    report.appendLine("Descriptor request returned $length. The device may require another descriptor path or deny this request.")
                }
                connection.releaseInterface(hid)
            } else report.appendLine("Android could not claim HID interface 3.")
        } catch (e: Exception) {
            report.appendLine("\nUSB inspection error: ${e.javaClass.simpleName}: ${e.message}")
        } finally {
            connection.close()
        }
        report.appendLine("\nSafety: no lighting/output/feature commands were transmitted.")
        output.text = report.toString()
    }

    private fun append(message: String) {
        output.text = output.text.toString() + "\n" + message
    }
    private fun hex(value: Int) = "0x" + String.format(Locale.US, "%04X", value)
    override fun onDestroy() {
        try { unregisterReceiver(permissionReceiver) } catch (_: Exception) {}
        super.onDestroy()
    }
}
