package top.jast.miracastreceiver.miracast

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Build
import android.view.Display
import timber.log.Timber
import java.util.Calendar
import java.util.Locale

/**
 * Builds the 128-byte EDID sent in the RTSP M3 `wfd_display_edid` parameter
 * (Miracast spec 6.1.6: "<block-count HEX> <EDID hex payload>").
 *
 * Everything comes from the device's real display: the current mode size and
 * refresh rate, the physical size derived from xdpi/ydpi, the manufacturer and
 * model strings, and the build year. No hard-coded panel data.
 */
object WfdDisplayEdid {

    private data class Timing(
        val clock10kHz: Int,
        val hActive: Int,
        val hBlank: Int,
        val hFront: Int,
        val hSync: Int,
        val vActive: Int,
        val vBlank: Int,
        val vFront: Int,
        val vSync: Int
    )

    fun toM3Value(context: Context): String? = runCatching {
        val bytes = build(context)
        "0001 " + bytes.joinToString("") { String.format(Locale.ROOT, "%02X", it) }
    }.onFailure { Timber.w(it, "EDID build failed") }.getOrNull()

    fun build(context: Context): ByteArray {
        val dm = context.resources.displayMetrics
        val display = (context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager)
            ?.getDisplay(Display.DEFAULT_DISPLAY)
        val width = dm.widthPixels.coerceAtLeast(640)
        val height = dm.heightPixels.coerceAtLeast(480)
        val refresh = (display?.refreshRate ?: 60f).toInt().coerceIn(24, 120)
        val timing = timingFor(width, height, refresh)

        // A projector has no fixed physical screen size, and Android's xdpi is a
        // UI density rather than a panel size. Use the framework's physical mode
        // size when it is plausible; otherwise use the 96 DPI size so Windows does
        // not change scaling on the extended display.
        val mode = display?.mode
        val modeW = mode?.physicalWidth ?: 0
        val modeH = mode?.physicalHeight ?: 0
        val hMm = if (modeW in 200..3000 && modeH in 100..2000) modeW
        else (width / 96.0 * 25.4).toInt()
        val vMm = if (modeW in 200..3000 && modeH in 100..2000) modeH
        else (height / 96.0 * 25.4).toInt()
        val hCm = (hMm / 10).coerceIn(1, 255)
        val vCm = (vMm / 10).coerceIn(1, 255)

        val edid = ByteArray(128)
        // Header
        byteArrayOf(0x00, 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(),
            0xFF.toByte(), 0xFF.toByte(), 0x00).copyInto(edid, 0)
        // Manufacturer (3 letters, 5 bits each) and product code.
        val mfg = manufacturerId(Build.MANUFACTURER)
        edid[8] = ((mfg shr 8) and 0xFF).toByte()
        edid[9] = (mfg and 0xFF).toByte()
        val product = (Build.MODEL ?: "Miracast").hashCode() and 0xFFFF
        edid[10] = (product and 0xFF).toByte()
        edid[11] = ((product shr 8) and 0xFF).toByte()
        // Serial number 0, week 1, year from the build time.
        val year = Calendar.getInstance().apply { timeInMillis = Build.TIME }
            .get(Calendar.YEAR).coerceIn(1990, 2245) - 1990
        edid[16] = 1
        edid[17] = year.toByte()
        // EDID 1.3, digital input, panel size, gamma 2.2, RGB + preferred timing.
        edid[18] = 1
        edid[19] = 3
        edid[20] = 0x80.toByte()
        edid[21] = hCm.toByte()
        edid[22] = vCm.toByte()
        edid[23] = 0x78
        edid[24] = 0x0A
        // sRGB chromaticity.
        byteArrayOf(0xEE.toByte(), 0x91.toByte(), 0xA3.toByte(), 0x54.toByte(), 0x4C,
            0x99.toByte(), 0x26, 0x0F, 0x50, 0x54).copyInto(edid, 25)
        // No established timings; four unused standard timings.
        for (i in 38 until 54) edid[i] = 0x01
        // Detailed timing descriptor for the real mode.
        detailedTiming(timing, hMm, vMm).copyInto(edid, 54)
        // Monitor name from Build.MODEL.
        nameDescriptor(Build.MODEL ?: "Miracast").copyInto(edid, 72)
        // Range limits compatible with the real refresh rate.
        rangeLimits(timing, refresh).copyInto(edid, 90)
        // Dummy descriptor.
        edid[108] = 0x00
        edid[109] = 0x00
        edid[110] = 0x00
        edid[111] = 0x10
        // No extension blocks.
        edid[126] = 0
        edid[127] = checksum(edid)
        return edid
    }

    private fun timingFor(width: Int, height: Int, refresh: Int): Timing = when {
        width == 1920 && height == 1080 ->
            Timing(14850, 1920, 280, 88, 44, 1080, 45, 4, 5)
        width == 1280 && height == 720 ->
            Timing(7425, 1280, 370, 110, 40, 720, 30, 5, 5)
        width == 3840 && height == 2160 ->
            Timing(59400, 3840, 560, 176, 88, 2160, 90, 8, 10)
        else -> {
            // CVT-RB style fallback for any other real mode.
            val hBlank = (width * 0.08f).toInt().coerceAtLeast(80)
            val vBlank = 30
            val clock10kHz = ((width + hBlank) * (height + vBlank) * refresh / 10000)
                .coerceAtLeast(1000)
            Timing(
                clock10kHz, width, hBlank, hBlank / 3, hBlank / 6,
                height, vBlank, 3, 5
            )
        }
    }

    private fun detailedTiming(t: Timing, hMm: Int, vMm: Int): ByteArray {
        val d = ByteArray(18)
        d[0] = (t.clock10kHz and 0xFF).toByte()
        d[1] = ((t.clock10kHz shr 8) and 0xFF).toByte()
        d[2] = (t.hActive and 0xFF).toByte()
        d[3] = (t.hBlank and 0xFF).toByte()
        d[4] = (((t.hActive shr 8) and 0x0F) shl 4 or ((t.hBlank shr 8) and 0x0F)).toByte()
        d[5] = (t.vActive and 0xFF).toByte()
        d[6] = (t.vBlank and 0xFF).toByte()
        d[7] = (((t.vActive shr 8) and 0x0F) shl 4 or ((t.vBlank shr 8) and 0x0F)).toByte()
        d[8] = (t.hFront and 0xFF).toByte()
        d[9] = (t.hSync and 0xFF).toByte()
        d[10] = (((t.vFront and 0x0F) shl 4) or (t.vSync and 0x0F)).toByte()
        d[11] = ((((t.hFront shr 8) and 0x03) shl 6) or
            (((t.hSync shr 8) and 0x03) shl 4) or
            (((t.vFront shr 4) and 0x03) shl 2) or
            ((t.vSync shr 4) and 0x03)).toByte()
        d[12] = (hMm and 0xFF).toByte()
        d[13] = (vMm and 0xFF).toByte()
        d[14] = (((hMm shr 8) and 0x0F) shl 4 or ((vMm shr 8) and 0x0F)).toByte()
        d[17] = 0x1A // digital separate sync, +H +V
        return d
    }

    private fun nameDescriptor(name: String): ByteArray {
        val d = ByteArray(18)
        d[3] = 0xFC.toByte()
        val text = name.take(13)
        for (i in 0 until 13) {
            d[5 + i] = if (i < text.length) text[i].code.toByte() else 0x20
        }
        if (text.length < 13) d[5 + text.length] = 0x0A
        return d
    }

    private fun rangeLimits(t: Timing, refresh: Int): ByteArray {
        val d = ByteArray(18)
        d[3] = 0xFD.toByte()
        d[5] = (refresh - 2).coerceIn(1, 255).toByte()
        d[6] = (refresh + 15).coerceIn(1, 255).toByte()
        val maxH = ((t.hActive + t.hBlank) * refresh / 1000).coerceIn(1, 255)
        d[7] = (maxH / 2).coerceIn(1, 255).toByte()
        d[8] = maxH.toByte()
        // Maximum pixel clock in 10 MHz units (round up so it covers the DTD).
        d[9] = (((t.clock10kHz + 999) / 1000).coerceIn(1, 255)).toByte()
        for (i in 10 until 18) d[i] = 0x0A
        return d
    }

    private fun manufacturerId(name: String): Int {
        val letters = name.uppercase(Locale.ROOT).filter { it in 'A'..'Z' }.padEnd(3, 'A').take(3)
        return ((letters[0] - 'A' + 1) shl 10) or
            ((letters[1] - 'A' + 1) shl 5) or
            (letters[2] - 'A' + 1)
    }

    private fun checksum(edid: ByteArray): Byte {
        var sum = 0
        for (i in 0 until 127) sum += edid[i].toInt() and 0xFF
        return ((256 - (sum % 256)) % 256).toByte()
    }
}
