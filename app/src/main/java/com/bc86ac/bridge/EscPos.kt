package com.bc86ac.bridge

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets

/**
 * Minimal ESC/POS command builder -- same command set used by the Chrome
 * extension's lib/escpos.js, kept here so the app can build its own test
 * page without depending on a caller to send pre-built bytes.
 */
class ReceiptBuilder {
    private val buf = ByteArrayOutputStream()

    fun init(): ReceiptBuilder { buf.write(byteArrayOf(0x1B, 0x40)); return this }

    fun align(pos: String): ReceiptBuilder {
        val n = when (pos) { "center" -> 1; "right" -> 2; else -> 0 }
        buf.write(byteArrayOf(0x1B, 0x61, n.toByte())); return this
    }

    fun bold(on: Boolean): ReceiptBuilder {
        buf.write(byteArrayOf(0x1B, 0x45, if (on) 1 else 0)); return this
    }

    fun doubleSize(on: Boolean): ReceiptBuilder {
        buf.write(byteArrayOf(0x1D, 0x21, if (on) 0x11 else 0x00)); return this
    }

    fun text(s: String): ReceiptBuilder {
        buf.write(s.toByteArray(StandardCharsets.UTF_8)); return this
    }

    fun line(s: String = ""): ReceiptBuilder { text(s); buf.write(0x0A); return this }

    fun feed(n: Int = 1): ReceiptBuilder { repeat(n) { buf.write(0x0A) }; return this }

    fun divider(width: Int = 32): ReceiptBuilder = line("-".repeat(width))

    fun cut(): ReceiptBuilder {
        buf.write(byteArrayOf(0x1D, 0x56, 0x01)); return this
    }

    fun build(): ByteArray = buf.toByteArray()
}

fun buildTestPage(): ByteArray = ReceiptBuilder()
    .init()
    .align("center")
    .doubleSize(true)
    .line("TEST PRINT")
    .doubleSize(false)
    .line("BC-86AC Print Bridge (App)")
    .divider()
    .align("left")
    .line("USB / Network connection OK")
    .line("Time: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(java.util.Date())}")
    .divider()
    .align("center")
    .bold(true)
    .line("Bridge is working")
    .bold(false)
    .feed(3)
    .cut()
    .build()
