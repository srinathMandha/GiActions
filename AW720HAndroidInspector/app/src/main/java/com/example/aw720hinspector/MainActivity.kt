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
                if (device != null && granted) runFullDiagnostic(device)
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
        layout.addView(TextView(this).apply {
            text = "Alienware AW720H Inspector"
            textSize = 23f
            setTextColor(0xFFFFFFFF.toInt())
        })
        layout.addView(TextView(this).apply {
            text = "Connect the AW720H 2.4 GHz dongle by USB OTG. The full diagnostic inspects every USB interface, audio class descriptors and readable audio controls, HID reports and interrupt input. It does not send output reports or change settings."
            textSize = 15f
            setTextColor(0xFFB9C7DD.toInt())
            setPadding(0, 18, 0, 18)
        })
        layout.addView(Button(this).apply {
            text = "Scan for AW720H dongle"
            setOnClickListener { scanDevices() }
        })
        layout.addView(Button(this).apply {
            text = "Run full read-only diagnostic"
            setOnClickListener { scanDevices() }
        })
        layout.addView(Button(this).apply {
            text = "Copy full debug log"
            setOnClickListener {
                val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("AW720H full debug log", output.text))
                Toast.makeText(this@MainActivity, "Full debug log copied. Paste it into ChatGPT.", Toast.LENGTH_LONG).show()
            }
        })
        output = TextView(this).apply {
            textSize = 12f
            typeface = android.graphics.Typeface.MONOSPACE
            setTextColor(0xFFD7E5FF.toInt())
            setPadding(0, 22, 0, 0)
            text = "Waiting for device…"
        }
        layout.addView(output)
        val scroll = ScrollView(this)
        scroll.addView(layout)
        setContentView(scroll)
        registerReceiver(permissionReceiver, IntentFilter(actionUsbPermission), RECEIVER_NOT_EXPORTED)
    }

    private fun scanDevices() {
        val devices = usbManager.deviceList.values
        val target = devices.firstOrNull { it.vendorId == 0x413C && it.productId == 0xA518 }
        if (target == null) {
            output.text = if (devices.isEmpty()) "No USB devices found. Check OTG adapter and reconnect the dongle."
            else devices.joinToString("\n\n") { "${it.deviceName}\nVID=${hex(it.vendorId)} PID=${hex(it.productId)}" } +
                "\n\nAW720H (413C:A518) not found."
            return
        }
        output.text = "Found ${target.deviceName}\nVID=${hex(target.vendorId)} PID=${hex(target.productId)}\nRequesting USB permission…"
        if (usbManager.hasPermission(target)) runFullDiagnostic(target) else {
            val intent = PendingIntent.getBroadcast(
                this, 0, Intent(actionUsbPermission).setPackage(packageName),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            )
            usbManager.requestPermission(target, intent)
        }
    }

    private fun runFullDiagnostic(device: UsbDevice) {
        val connection = usbManager.openDevice(device)
        if (connection == null) {
            output.text = "Could not open USB device."
            return
        }
        output.text = "Running full read-only USB diagnostic…"
        Thread {
            val log = StringBuilder()
            try {
                logLine(log, "AW720H FULL READ-ONLY USB DIAGNOSTIC")
                logLine(log, "Timestamp: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSSZ", Locale.US).format(java.util.Date())}")
                logLine(log, "Android: ${android.os.Build.VERSION.RELEASE} (SDK ${android.os.Build.VERSION.SDK_INT})")
                logLine(log, "Manufacturer/model: ${android.os.Build.MANUFACTURER} / ${android.os.Build.MODEL}")
                logLine(log, "App package: $packageName")
                log.appendLine("Device: ${device.deviceName} VID=${hex(device.vendorId)} PID=${hex(device.productId)}")
                log.appendLine("Interfaces reported by Android: ${device.interfaceCount}")
                log.appendLine("No USB OUT transfers, HID output reports or settings-changing requests are sent.")
                val config = ByteArray(4096)
                val configLen = connection.controlTransfer(0x80, 0x06, 0x0200, 0, config, config.size, 2000)
                log.appendLine("\n=== RAW CONFIGURATION DESCRIPTOR (length=$configLen) ===")
                if (configLen > 0) {
                    log.appendLine(config.take(configLen).joinToString(" ") { "%02x".format(it) })
                    parseConfiguration(config, configLen, log)
                } else log.appendLine("Configuration descriptor request returned $configLen")

                for (i in 0 until device.interfaceCount) {
                    val intf = device.getInterface(i)
                    log.appendLine("\n=== INTERFACE id=${intf.id} alt=${intf.alternateSetting} class=${hex(intf.interfaceClass)} subclass=${hex(intf.interfaceSubclass)} protocol=${hex(intf.interfaceProtocol)} ===")
                    val claimed = try { connection.claimInterface(intf, true) } catch (e: Exception) {
                        log.appendLine("Claim error: ${e.message}"); false
                    }
                    log.appendLine("Claimed: $claimed")
                    if (!claimed) continue
                    try {
                        // Standard GET_STATUS: read-only, recipient=interface.
                        val status = ByteArray(2)
                        val statusLen = connection.controlTransfer(0x81, 0x00, 0, intf.id, status, 2, 500)
                        log.appendLine("Standard interface GET_STATUS: len=$statusLen data=${bytes(status, statusLen)}")

                        if (intf.interfaceClass == UsbConstants.USB_CLASS_HID) {
                            inspectHid(connection, intf, log)
                        } else if (intf.interfaceClass == UsbConstants.USB_CLASS_AUDIO) {
                            log.appendLine("Audio class interface: class-specific descriptors are included in the raw configuration dump above.")
                            queryAudioFeatureUnits(connection, config, configLen, intf.id, log)
                        } else {
                            log.appendLine("No class-specific control requests sent for this interface; descriptor and standard status inspection only.")
                        }

                        for (e in 0 until intf.endpointCount) {
                            val ep = intf.getEndpoint(e)
                            log.appendLine("Endpoint ${ep.endpointNumber}: direction=${if (ep.direction == UsbConstants.USB_DIR_IN) "IN" else "OUT"}, type=${ep.type}, maxPacket=${ep.maxPacketSize}, interval=${ep.interval}")
                        }
                    } finally {
                        try { connection.releaseInterface(intf) } catch (_: Exception) {}
                    }
                }
                // Read interrupt IN endpoints on HID interfaces after descriptor/control inspection.
                log.appendLine("\n=== HID INTERRUPT INPUT CAPTURE (5 seconds total) ===")
                val hidInterfaces = (0 until device.interfaceCount).map { device.getInterface(it) }
                    .filter { it.interfaceClass == UsbConstants.USB_CLASS_HID }
                val endTime = System.currentTimeMillis() + 5000
                for (intf in hidInterfaces) {
                    val ep = (0 until intf.endpointCount).map { intf.getEndpoint(it) }.firstOrNull {
                        it.direction == UsbConstants.USB_DIR_IN && it.type == UsbConstants.USB_ENDPOINT_XFER_INT
                    } ?: continue
                    if (System.currentTimeMillis() >= endTime) break
                    if (!connection.claimInterface(intf, true)) {
                        log.appendLine("HID interface ${intf.id}: could not claim for interrupt capture")
                        continue
                    }
                    try {
                        val buffer = ByteArray(ep.maxPacketSize.coerceAtLeast(8))
                        var packetCount = 0
                        while (System.currentTimeMillis() < endTime) {
                            val n = connection.bulkTransfer(ep, buffer, buffer.size, 250)
                            if (n > 0) {
                                packetCount++
                                log.appendLine("HID interface ${intf.id} packet $packetCount: ${bytes(buffer, n)}")
                            }
                        }
                        if (packetCount == 0) log.appendLine("HID interface ${intf.id}: no interrupt packets during capture")
                    } finally {
                        try { connection.releaseInterface(intf) } catch (_: Exception) {}
                    }
                }
                log.appendLine("\n=== END ===")
                log.appendLine("Read-only requests performed: configuration/report descriptors, standard interface GET_STATUS, HID GET_REPORT for input/feature reports declared by the report descriptor, and Audio Class GET_CUR for discovered mute controls.")
                log.appendLine("No output report or USB OUT transfer was sent.")
            } catch (e: Exception) {
                log.appendLine("\nDiagnostic error: ${e.javaClass.simpleName}: ${e.message}")
            } finally {
                connection.close()
                runOnUiThread { output.text = log.toString() }
            }
        }.start()
    }

    private fun inspectHid(c: UsbDeviceConnection, intf: UsbInterface, log: StringBuilder) {
        val desc = ByteArray(2048)
        val n = c.controlTransfer(0x81, 0x06, 0x2200, intf.id, desc, desc.size, 1500)
        log.appendLine("HID report descriptor length=$n")
        if (n <= 0) return
        log.appendLine("HID report descriptor: ${bytes(desc, n)}")
        val reports = parseHidReports(desc, n)
        for ((key, size) in reports) {
            val type = key.first
            val id = key.second
            if (type != 1 && type != 3) continue // Input and Feature GET_REPORT only; never Output.
            val buffer = ByteArray((size + 1).coerceAtMost(1024))
            val result = c.controlTransfer(0xA1, 0x01, (type shl 8) or id, intf.id, buffer, buffer.size, 500)
            log.appendLine("HID GET_REPORT type=$type id=0x${id.toString(16)} expectedPayload=$size result=$result data=${bytes(buffer, result)}")
        }
    }

    private fun parseHidReports(data: ByteArray, length: Int): Map<Pair<Int, Int>, Int> {
        val result = linkedMapOf<Pair<Int, Int>, Int>()
        var pos = 0
        var reportSize = 0
        var reportCount = 0
        var reportId = 0
        while (pos < length) {
            val b = data[pos++].toInt() and 0xff
            if (b == 0xFE) {
                if (pos + 1 >= length) break
                val size = data[pos].toInt() and 0xff
                pos += 2 + size
                continue
            }
            val sizeCode = b and 3
            val size = when (sizeCode) { 3 -> 4; else -> sizeCode }
            if (pos + size > length) break
            var value = 0
            for (j in 0 until size) value = value or ((data[pos++].toInt() and 0xff) shl (8 * j))
            when ((b shr 2) and 3) {
                1 -> when ((b shr 4) and 15) {
                    7 -> reportSize = value
                    8 -> reportId = value
                    9 -> reportCount = value
                }
                0 -> {
                    val tag = (b shr 4) and 15
                    if (tag == 8 || tag == 11) {
                        val type = if (tag == 8) 1 else 3
                        val bits = reportSize.toLong() * reportCount
                        val bytes = ((bits + 7) / 8).toInt()
                        val k = type to reportId
                        result[k] = (result[k] ?: 0) + bytes
                    }
                }
            }
        }
        return result
    }

    private fun queryAudioFeatureUnits(c: UsbDeviceConnection, config: ByteArray, length: Int, interfaceId: Int, log: StringBuilder) {
        if (length <= 0) return
        var pos = 0
        var found = 0
        while (pos + 2 <= length) {
            val size = config[pos].toInt() and 0xff
            if (size < 2 || pos + size > length) break
            val type = config[pos + 1].toInt() and 0xff
            if (type == 0x24 && size >= 7) {
                val subtype = config[pos + 2].toInt() and 0xff
                if (subtype == 0x06) { // Audio Feature Unit descriptor
                    val unitId = config[pos + 3].toInt() and 0xff
                    val controlSize = config[pos + 5].toInt() and 0xff
                    if (controlSize in 1..4 && size >= 6 + controlSize) {
                        val controls = (0 until controlSize).fold(0L) { acc, j ->
                            acc or ((config[pos + 6 + j].toLong() and 0xff) shl (8 * j))
                        }
                        log.appendLine("Audio Feature Unit candidate: interface=$interfaceId unit=$unitId controlSize=$controlSize masterControls=0x${controls.toString(16)}")
                        if ((controls and 1L) != 0L) {
                            val value = ByteArray(4)
                            val idx = (unitId shl 8) or interfaceId
                            val got = c.controlTransfer(0xA1, 0x81, 0x0100, idx, value, 1, 700)
                            log.appendLine("Audio Class GET_CUR mute (unit=$unitId channel=0): len=$got value=${bytes(value, got)} (0=unmuted, 1=muted when supported)")
                            found++
                        } else log.appendLine("Mute control bit not advertised in master channel; no mute query sent.")
                    }
                }
            }
            pos += size
        }
        if (found == 0) log.appendLine("No advertised Audio Feature Unit mute control found for this interface.")
    }

    private fun parseConfiguration(data: ByteArray, length: Int, log: StringBuilder) {
        var pos = 0
        var currentInterface = -1
        while (pos + 2 <= length) {
            val size = data[pos].toInt() and 0xff
            if (size < 2 || pos + size > length) break
            val type = data[pos + 1].toInt() and 0xff
            if (type == 4 && size >= 9) currentInterface = data[pos + 2].toInt() and 0xff
            if (type == 0x24 || type == 0x25 || type == 0x21) {
                log.appendLine("Class-specific descriptor type=0x${type.toString(16)} interface=$currentInterface: ${bytes(data.copyOfRange(pos, pos + size), size)}")
            }
            pos += size
        }
    }

    private fun logLine(log: StringBuilder, message: String) {
        val stamp = java.text.SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(java.util.Date())
        log.append("[").append(stamp).append("] ").append(message).append('\\n')
    }

    private fun bytes(data: ByteArray, length: Int): String =
        if (length <= 0) "(no data)" else data.take(length.coerceAtMost(data.size)).joinToString(" ") { "%02x".format(it) }

    private fun append(message: String) {
        output.text = output.text.toString() + "\n" + message
    }

    private fun hex(value: Int) = "0x" + String.format(Locale.US, "%04X", value)

    override fun onDestroy() {
        try { unregisterReceiver(permissionReceiver) } catch (_: Exception) {}
        super.onDestroy()
    }
}
