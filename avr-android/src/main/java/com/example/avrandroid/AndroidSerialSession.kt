package com.example.avrandroid

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Live USB serial session for Serial Monitor.
 * It reuses the same proven USB transport used by the avrdude bridge; no AVR protocol is run here.
 */
class AndroidSerialSession(
    context: Context,
    private val device: UsbDevice,
    private val baudRate: Int,
    private val charset: Charset = StandardCharsets.UTF_8,
    private val listener: Listener
) : AutoCloseable {
    interface Listener {
        fun onOpened()
        fun onBytes(bytes: ByteArray)
        fun onError(message: String)
        fun onClosed()
    }

    private val appContext = context.applicationContext
    private val ioExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val running = AtomicBoolean(false)
    private var transport: UsbSerialTransport? = null
    private var readerThread: Thread? = null

    fun start() {
        if (!running.compareAndSet(false, true)) return
        ioExecutor.execute {
            try {
                val manager = appContext.getSystemService(Context.USB_SERVICE) as UsbManager
                if (!manager.hasPermission(device)) throw IllegalStateException("USB permission is not granted")
                val opened = UsbSerialTransport.open(
                    manager = manager,
                    device = device,
                    baud = baudRate,
                    log = { message -> listener.onError(message) },
                    initialize = true
                ) ?: throw IllegalStateException("No supported USB serial interface found")
                transport = opened
                listener.onOpened()
                readerThread = Thread { readLoop() }.apply {
                    name = "luma-serial-reader"
                    isDaemon = true
                    start()
                }
            } catch (error: Exception) {
                running.set(false)
                closeTransport()
                listener.onError(error.message ?: error.javaClass.simpleName)
            }
        }
    }

    fun sendText(text: String, appendNewline: Boolean = true) {
        if (!running.get()) {
            listener.onError("Serial Monitor is not connected")
            return
        }
        val payload = (if (appendNewline) "$text\n" else text).toByteArray(charset)
        ioExecutor.execute {
            try {
                transport?.write(payload) ?: throw IllegalStateException("Serial transport is closed")
            } catch (error: Exception) {
                listener.onError("Send failed: ${error.message ?: error.javaClass.simpleName}")
            }
        }
    }

    override fun close() {
        if (running.getAndSet(false)) {
            runCatching { readerThread?.interrupt() }
            closeTransport()
            listener.onClosed()
        }
        ioExecutor.shutdownNow()
    }

    private fun readLoop() {
        val buffer = ByteArray(512)
        try {
            while (running.get()) {
                val received = transport?.readChunk(buffer, READ_TIMEOUT_MS) ?: -1
                when {
                    received > 0 -> listener.onBytes(buffer.copyOf(received))
                    received == 0 -> Thread.yield()
                    else -> {
                        // UsbDeviceConnection.bulkTransfer returns -1 on timeout on many Android versions.
                        // A timeout is not a disconnect; keep polling until close() sets running=false.
                        Thread.yield()
                    }
                }
            }
        } catch (error: Exception) {
            if (running.get()) listener.onError("Serial read failed: ${error.message ?: error.javaClass.simpleName}")
        }
    }

    private fun closeTransport() {
        runCatching { transport?.close() }
        transport = null
    }

    companion object {
        private const val READ_TIMEOUT_MS = 250
    }
}
