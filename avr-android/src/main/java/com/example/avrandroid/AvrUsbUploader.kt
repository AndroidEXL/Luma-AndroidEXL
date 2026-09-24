package com.example.avrandroid

import android.content.Context
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.SystemClock
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.min

private const val CDC_SET_LINE_CODING = 0x20
private const val CDC_SET_CONTROL_LINE_STATE = 0x22

class AvrUploader(context: Context) : AutoCloseable {
    private val appContext = context.applicationContext
    private val executor = Executors.newSingleThreadExecutor()

    fun upload(request: AvrUploadRequest, callback: AvrUploadCallback) {
        executor.execute { callback(run(request)) }
    }

    override fun close() {
        executor.shutdownNow()
    }

    private fun run(request: AvrUploadRequest): AvrUploadResult {
        val output = StringBuilder()
        fun log(value: String) { output.append(value).append('\n') }
        if (!request.hexFile.isFile) return AvrUploadResult(false, "HEX file not found: ${request.hexFile.absolutePath}")
        val manager = appContext.getSystemService(Context.USB_SERVICE) as UsbManager
        if (!manager.hasPermission(request.usbDevice)) {
            return AvrUploadResult(false, "USB permission is not granted for ${request.usbDevice.deviceName}")
        }
        if (request.board.uses1200Touch) {
            return AvrUploadResult(
                false,
                "${request.board.name} uses the 1200-baud USB bootloader touch and may re-enumerate. Reopen Select Port after reset; AVR109 upload is reserved for the next transport module."
            )
        }
        if (request.board == AvrBoard.MEGA) {
            return AvrUploadResult(
                false,
                "Mega 2560 is selected. Native avrdude is packaged, but Android USB Host STK500v2 transport is not enabled in this build; use UNO/Nano/Pro Mini transport or wait for the STK500v2 module."
            )
        }

        val transport = UsbSerialTransport.open(manager, request.usbDevice, request.board.uploadBaudRate, ::log)
            ?: return AvrUploadResult(false, output.toString() + "Unable to open a supported USB serial interface.")
        return try {
            val image = AvrHexImage.parse(request.hexFile)
            val pages = image.pages(request.board.flashPageSize)
            log("Board: ${request.board.fqbn}")
            log("Protocol: STK500v1 / ${request.board.programmer}")
            log("Baud: ${request.board.uploadBaudRate}")
            log("HEX: ${request.hexFile.absolutePath}")
            log("Pages: ${pages.size}, bytes: ${pages.sumOf { it.usedBytes }}")
            val protocol = Stk500v1Protocol(transport, output)
            protocol.upload(pages, request.board, ::log)
            AvrUploadResult(true, output.toString(), pages.sumOf { it.usedBytes })
        } catch (error: Exception) {
            log("ERROR: ${error.message ?: error.javaClass.simpleName}")
            AvrUploadResult(false, output.toString())
        } finally {
            transport.close()
        }
    }
}

internal interface UsbSerialTransport {
    fun configure()
    fun pulseReset()
    fun write(bytes: ByteArray)
    fun write(bytes: ByteArray, length: Int) {
        write(bytes.copyOf(length))
    }
    fun readChunk(buffer: ByteArray, timeoutMs: Int): Int
    fun readExact(count: Int, timeoutMs: Int = 3_000): ByteArray
    fun purge()
    fun close()

    companion object {
        private const val CH34X_VENDOR_ID = 0x1A86
        private const val CH34X_PRODUCT_ID = 0x7523
        private const val CH34X_PRODUCT_ID_ALT = 0x5523
        private const val FTDI_VENDOR_ID = 0x0403

        fun open(
            manager: UsbManager,
            device: UsbDevice,
            baud: Int,
            log: (String) -> Unit,
            initialize: Boolean = true
        ): UsbSerialTransport? {
            val connection = manager.openDevice(device) ?: return null
            val usbInterface = findInterface(device)
            if (usbInterface == null) {
                connection.close()
                return null
            }
            if (!connection.claimInterface(usbInterface, true)) {
                connection.close()
                return null
            }
            val endpoints = findEndpoints(usbInterface)
            if (endpoints.first == null || endpoints.second == null) {
                connection.releaseInterface(usbInterface)
                connection.close()
                return null
            }
            val controlInterfaceId = findControlInterfaceId(device, usbInterface)
            val transport = AndroidUsbSerialTransport(connection, usbInterface, controlInterfaceId, endpoints.first!!, endpoints.second!!, device, baud, log)
            return runCatching {
                if (initialize) {
                    transport.configure()
                    transport.pulseReset()
                    transport.purge()
                }
                transport
            }.getOrElse {
                log("USB serial setup failed: ${it.message}")
                transport.close()
                null
            }
        }

        private fun findInterface(device: UsbDevice): UsbInterface? {
            for (index in 0 until device.interfaceCount) {
                val candidate = device.getInterface(index)
                if (candidate.interfaceClass == UsbConstants.USB_CLASS_CDC_DATA || candidate.interfaceClass == UsbConstants.USB_CLASS_VENDOR_SPEC) {
                    if ((0 until candidate.endpointCount).any { candidate.getEndpoint(it).direction == UsbConstants.USB_DIR_IN } &&
                        (0 until candidate.endpointCount).any { candidate.getEndpoint(it).direction == UsbConstants.USB_DIR_OUT }) return candidate
                }
            }
            return (0 until device.interfaceCount).asSequence()
                .map { device.getInterface(it) }
                .firstOrNull { candidate ->
                    (0 until candidate.endpointCount).any { candidate.getEndpoint(it).type == UsbConstants.USB_ENDPOINT_XFER_BULK && candidate.getEndpoint(it).direction == UsbConstants.USB_DIR_IN } &&
                        (0 until candidate.endpointCount).any { candidate.getEndpoint(it).type == UsbConstants.USB_ENDPOINT_XFER_BULK && candidate.getEndpoint(it).direction == UsbConstants.USB_DIR_OUT }
                }
        }

        private fun findControlInterfaceId(device: UsbDevice, dataInterface: UsbInterface): Int {
            if (dataInterface.interfaceClass != UsbConstants.USB_CLASS_CDC_DATA) return 0
            for (index in 0 until device.interfaceCount) {
                val candidate = device.getInterface(index)
                if (candidate.interfaceClass == UsbConstants.USB_CLASS_COMM) return candidate.id
            }
            return 0
        }

        private fun findEndpoints(usbInterface: UsbInterface): Pair<UsbEndpoint?, UsbEndpoint?> {
            var input: UsbEndpoint? = null
            var output: UsbEndpoint? = null
            for (index in 0 until usbInterface.endpointCount) {
                val endpoint = usbInterface.getEndpoint(index)
                if (endpoint.type != UsbConstants.USB_ENDPOINT_XFER_BULK) continue
                if (endpoint.direction == UsbConstants.USB_DIR_IN) input = endpoint
                if (endpoint.direction == UsbConstants.USB_DIR_OUT) output = endpoint
            }
            return input to output
        }
    }
}

internal class AndroidUsbSerialTransport(
    private val connection: UsbDeviceConnection,
    private val usbInterface: UsbInterface,
    private val controlInterfaceId: Int,
    private val inputEndpoint: UsbEndpoint,
    private val outputEndpoint: UsbEndpoint,
    private val device: UsbDevice,
    private val baud: Int,
    private val log: (String) -> Unit
) : UsbSerialTransport {
    private val isCh34x = device.vendorId == 0x1A86 && (device.productId == 0x7523 || device.productId == 0x5523)
    private val isFtdi = device.vendorId == 0x0403
    private val isCdc = usbInterface.interfaceClass == UsbConstants.USB_CLASS_CDC_DATA

    override fun configure() {
        when {
            isCh34x -> configureCh34x()
            isFtdi -> configureFtdi()
            isCdc -> configureCdc()
            else -> log("USB serial driver: generic bulk transport")
        }
        log("USB device ${device.deviceName} configured at $baud baud")
    }

    override fun pulseReset() {
        if (isCdc) {
            cdcControl(CDC_SET_CONTROL_LINE_STATE, 0, null)
            SystemClock.sleep(80)
            cdcControl(CDC_SET_CONTROL_LINE_STATE, 3, null)
            SystemClock.sleep(350)
        } else if (isFtdi) {
            control(1, 0, 0, null)
            SystemClock.sleep(80)
            control(1, 1, 0, null)
            SystemClock.sleep(350)
        }
    }

    override fun write(bytes: ByteArray) {
        var offset = 0
        while (offset < bytes.size) {
            val end = min(bytes.size, offset + 256)
            val chunk = bytes.copyOfRange(offset, end)
            val sent = connection.bulkTransfer(outputEndpoint, chunk, chunk.size, 3_000)
            if (sent != chunk.size) throw IllegalStateException("USB bulk write failed: $sent/${chunk.size}")
            offset = end
        }
    }

    override fun readChunk(buffer: ByteArray, timeoutMs: Int): Int =
        connection.bulkTransfer(inputEndpoint, buffer, buffer.size, timeoutMs)

    override fun readExact(count: Int, timeoutMs: Int): ByteArray {
        val result = ByteArray(count)
        var offset = 0
        val started = SystemClock.elapsedRealtime()
        while (offset < count) {
            val elapsed = SystemClock.elapsedRealtime() - started
            val remaining = timeoutMs - elapsed
            if (remaining <= 0) throw IllegalStateException("USB serial read timeout after $offset/$count bytes")
            val buffer = ByteArray(count - offset)
            val received = connection.bulkTransfer(inputEndpoint, buffer, buffer.size, remaining.toInt().coerceAtLeast(1))
            if (received <= 0) continue
            buffer.copyInto(result, offset, 0, received)
            offset += received
        }
        return result
    }

    override fun purge() {
        val scratch = ByteArray(inputEndpoint.maxPacketSize.coerceAtLeast(64))
        repeat(4) {
            val received = connection.bulkTransfer(inputEndpoint, scratch, scratch.size, 20)
            if (received <= 0) return
        }
    }

    override fun close() {
        runCatching { connection.releaseInterface(usbInterface) }
        connection.close()
    }

    private fun configureCdc() {
        val lineCoding = ByteArray(7).apply {
            this[0] = (baud and 0xFF).toByte()
            this[1] = ((baud shr 8) and 0xFF).toByte()
            this[2] = ((baud shr 16) and 0xFF).toByte()
            this[3] = ((baud shr 24) and 0xFF).toByte()
            this[4] = 0
            this[5] = 0
            this[6] = 8
        }
        cdcControl(CDC_SET_LINE_CODING, 0, lineCoding)
    }

    private fun cdcControl(request: Int, value: Int, data: ByteArray?) {
        val result = connection.controlTransfer(
            UsbConstants.USB_DIR_OUT or UsbConstants.USB_TYPE_CLASS or 0x01,
            request,
            value,
            controlInterfaceId,
            data,
            data?.size ?: 0,
            3_000
        )
        if (result < 0) throw IllegalStateException("CDC control transfer $request failed: $result")
    }

    private fun configureCh34x() {
        control(0xA1, 0x0000, 0x0000, null)
        control(0x9A, 0x1312, 0xB282, null)
        control(0x9A, 0x2518, 0x0050, null)
        val divisor = ch34xBaudDivisor(baud)
        control(0x9A, divisor and 0xFFFF, (divisor shr 16) and 0xFFFF, null)
        control(0xA1, 0x0000, 0x0000, null)
    }

    private fun configureFtdi() {
        val divisor = ftdiBaudDivisor(baud)
        control(3, divisor, 0, null)
        control(1, 0x0101, 0, null)
    }

    private fun ch34xBaudDivisor(baud: Int): Int = when (baud) {
        115200 -> 0xCC83
        57600 -> 0xD983
        38400 -> 0xE683
        9600 -> 0xB282
        else -> 0xCC83
    }

    private fun ftdiBaudDivisor(baud: Int): Int = when (baud) {
        115200 -> 0x001A
        57600 -> 0x0034
        38400 -> 0x004A
        9600 -> 0x4138
        else -> 0x001A
    }

    private fun control(request: Int, value: Int, index: Int, data: ByteArray?) {
        val result = connection.controlTransfer(UsbConstants.USB_DIR_OUT or UsbConstants.USB_TYPE_VENDOR, request, value, index, data, data?.size ?: 0, 3_000)
        if (result < 0 && !(request == 0xA1 && isCh34x)) throw IllegalStateException("USB control transfer $request failed: $result")
    }
}

private class Stk500v1Protocol(
    private val transport: UsbSerialTransport,
    private val output: StringBuilder
) {
    private fun log(message: String) { output.append(message).append('\n') }

    fun upload(pages: List<AvrHexPage>, board: AvrBoard, progress: (String) -> Unit) {
        sync()
        setDevice(board)
        command(byteArrayOf(0x50, 0x20), 2, "enter programming mode")
        progress("Bootloader synchronized; programming mode entered")
        var complete = 0
        for (page in pages) {
            loadAddress(page.byteAddress / 2)
            val frame = ByteArray(5 + page.bytes.size + 1)
            frame[0] = 0x64
            frame[1] = ((page.bytes.size shr 8) and 0xFF).toByte()
            frame[2] = (page.bytes.size and 0xFF).toByte()
            frame[3] = 'F'.code.toByte()
            page.bytes.copyInto(frame, 4)
            frame[frame.lastIndex] = 0x20
            command(frame, 2, "program page 0x${page.byteAddress.toString(16)}")
            complete += page.usedBytes
            progress("Uploaded page 0x${page.byteAddress.toString(16)} — $complete bytes")
        }
        command(byteArrayOf(0x51, 0x20), 2, "leave programming mode")
        progress("Upload complete")
    }

    private fun sync() {
        repeat(8) { attempt ->
            runCatching {
                command(byteArrayOf(0x30, 0x20), 2, "sync")
                log("STK500v1 sync succeeded on attempt ${attempt + 1}")
                return
            }
            SystemClock.sleep(80)
        }
        error("STK500v1 bootloader did not respond to GET_SYNC")
    }

    private fun setDevice(board: AvrBoard) {
        val pageSize = board.flashPageSize
        val devCode = if (board.mcu == "atmega328p") 0x86 else 0x98
        val frame = ByteArray(22)
        frame[0] = 0x42
        frame[1] = devCode.toByte()
        frame[2] = 0
        frame[3] = 0
        frame[4] = 0
        frame[5] = 1
        frame[6] = 1
        frame[7] = 0
        frame[8] = 0
        frame[9] = 0xFF.toByte()
        frame[10] = 0xFF.toByte()
        frame[11] = 0xFF.toByte()
        frame[12] = 0xFF.toByte()
        frame[13] = ((pageSize shr 8) and 0xFF).toByte()
        frame[14] = (pageSize and 0xFF).toByte()
        frame[15] = 0
        frame[16] = 0
        frame[17] = 0
        frame[18] = 0x08
        frame[19] = 0
        frame[20] = 0
        frame[21] = 0x20
        command(frame, 2, "set device parameters")
    }

    private fun loadAddress(wordAddress: Int) {
        val frame = byteArrayOf(
            0x55,
            (wordAddress and 0xFF).toByte(),
            ((wordAddress shr 8) and 0xFF).toByte(),
            0x00,
            0x00,
            0x20
        )
        command(frame, 2, "load address")
    }

    private fun command(frame: ByteArray, responseLength: Int, description: String) {
        transport.write(frame)
        val response = transport.readExact(responseLength)
        if (response[0].toInt() and 0xFF != 0x14 || response[1].toInt() and 0xFF != 0x10) {
            throw IllegalStateException("$description failed: ${response.toHex()}")
        }
    }
}

private fun ByteArray.toHex(): String = joinToString(" ") { String.format(Locale.US, "%02X", it.toInt() and 0xFF) }
