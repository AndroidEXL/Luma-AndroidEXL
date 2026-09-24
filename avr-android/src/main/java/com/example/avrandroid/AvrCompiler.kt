package com.example.avrandroid

import android.content.Context
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

class AvrCompiler(context: Context) : AutoCloseable {

    private val appContext = context.applicationContext
    private val executor = Executors.newSingleThreadExecutor()
    private val nativeEngine = NativeAvrEngine()
    private val diagnosticPattern = Pattern.compile("^(.+):(\\d+):(\\d+):\\s+(error|warning|note):\\s+(.*)$")

    fun verify(request: AvrCompileRequest, callback: AvrCompileCallback) {
        executor.execute { callback(run(request, produceHex = false)) }
    }

    fun compile(request: AvrCompileRequest, callback: AvrCompileCallback) {
        executor.execute { callback(run(request, produceHex = true)) }
    }

    fun nativeVersion(): String = nativeEngine.version()

    override fun close() {
        executor.shutdownNow()
    }

    private fun run(request: AvrCompileRequest, produceHex: Boolean): AvrCompileResult {
        val nativeScan = nativeEngine.quickScan(request.code)
        val installation = if (request.toolchainRoot == null) {
            AvrToolchainInstaller.ensureInstalled(appContext)
        } else {
            AvrToolchainInstaller.Result(true, File(request.toolchainRoot), "تم استخدام toolchain مخصصة")
        }
        if (!installation.installed) return AvrCompileResult.ToolchainUnavailable(installation.message)

        val toolchainRoot = installation.root
        val compiler = File(toolchainRoot, "bin/avr-g++")
        val cCompiler = File(toolchainRoot, "bin/avr-gcc")
        val objcopy = File(toolchainRoot, "bin/avr-objcopy")
        val sizeTool = File(toolchainRoot, "bin/avr-size")
        val core = File(toolchainRoot, "avr-core/cores/arduino")
        val variant = File(toolchainRoot, "avr-core/variants/${request.board.variant}")
        val deviceSpecs = File(toolchainRoot, "lib/gcc/avr/7.3.0/device-specs/specs-${request.board.mcu}")
        if (!compiler.isFile || !compiler.canExecute() || !cCompiler.isFile || !objcopy.isFile || !sizeTool.isFile) {
            return AvrCompileResult.ToolchainUnavailable("AVR compiler tools غير مكتملة داخل ${toolchainRoot.absolutePath}")
        }
        if (!File(core, "Arduino.h").isFile || !File(variant, "pins_arduino.h").isFile) {
            return AvrCompileResult.ToolchainUnavailable("Arduino AVR core أو variant غير موجود داخل ${toolchainRoot.absolutePath}")
        }
        if (!deviceSpecs.isFile) {
            return AvrCompileResult.ToolchainUnavailable("ملف device-specs غير موجود للوحة ${request.board.mcu}: ${deviceSpecs.absolutePath}")
        }
        val linkerScript = File(toolchainRoot, "avr/lib/ldscripts/${linkerScriptName(request.board)}")
        if (produceHex && !linkerScript.isFile) {
            return AvrCompileResult.ToolchainUnavailable("ملف linker script غير موجود للوحة ${request.board.mcu}: ${linkerScript.absolutePath}")
        }

        val workDir = File(appContext.cacheDir, "avr_${if (produceHex) "build" else "verify"}_${System.currentTimeMillis()}").apply { mkdirs() }
        return try {
            val sourceFile = File(workDir, "${safeName(request.projectName)}.cpp")
            sourceFile.writeText(buildSource(request.code))
            val libraryRoots = request.libraryDirectories.map(::File).filter { it.isDirectory }.distinctBy { it.absolutePath }
            val includeDirectories = (libraryRoots.flatMap(::discoverLibraryIncludeDirectories) + core + variant +
                File(toolchainRoot, "avr/include") + File(toolchainRoot, "lib/gcc/avr/7.3.0/include"))
                .distinctBy { it.absolutePath }
            val librarySources = libraryRoots.flatMap(::discoverLibrarySources).distinctBy { it.absolutePath }
            val output = StringBuilder()
            val environment = buildEnvironment(toolchainRoot)
            val common = commonArguments(request, toolchainRoot, deviceSpecs, includeDirectories)

            if (!produceHex) {
                val command = mutableListOf(compiler.absolutePath, "-fsyntax-only")
                command += common
                command += listOf("-std=gnu++11", "-Os", "-fdiagnostics-color=never", "-fno-diagnostics-show-caret", "-Wall", "-Wextra", sourceFile.absolutePath)
                command += librarySources.map { it.absolutePath }
                val result = runProcess(command, workDir, environment)
                output.append(result.output)
                val diagnostics = parseDiagnostics(output.toString())
                return if (result.exitCode == 0 && diagnostics.none { it.severity == AvrDiagnostic.Severity.ERROR }) {
                    AvrCompileResult.Success(output.toString().ifBlank { "AVR-G++ verification completed" }, nativeEngine.version())
                } else {
                    AvrCompileResult.Failure(output.toString(), diagnostics, nativeEngine.version())
                }
            }

            val objects = mutableListOf<File>()
            val sourceInputs = listOf(sourceFile) +
                core.walkTopDown().filter { it.isFile && it.extension.lowercase() in setOf("c", "cpp") }.toList() +
                librarySources
            for ((index, input) in sourceInputs.withIndex()) {
                val outputObject = File(workDir, "obj_$index.o")
                val isC = input.extension.equals("c", true)
                val command = mutableListOf(if (isC) cCompiler.absolutePath else compiler.absolutePath, "-c")
                command += common
                command += listOf("-Os", "-ffunction-sections", "-fdata-sections", "-fdiagnostics-color=never", "-fno-diagnostics-show-caret")
                if (isC) command += listOf("-x", "c", "-std=gnu11") else command += listOf("-std=gnu++11")
                command += listOf(input.absolutePath, "-o", outputObject.absolutePath)
                val result = runProcess(command, workDir, environment)
                output.append("$ ${command.joinToString(" ")}\n").append(result.output)
                if (result.exitCode != 0) {
                    return AvrCompileResult.Failure(output.toString(), parseDiagnostics(output.toString()), nativeEngine.version())
                }
                objects += outputObject
            }

            val elf = File(workDir, "${safeName(request.projectName)}.elf")
            val linkCommand = mutableListOf(compiler.absolutePath)
            linkCommand += common
            linkCommand += listOf(
                "-Os",
                "-Wl,--gc-sections",
                "-Wl,-Map,${File(workDir, "${safeName(request.projectName)}.map").absolutePath}",
                "-Wl,-T,${linkerScript.absolutePath}"
            )
            linkCommand += objects.map { it.absolutePath }
            linkCommand += listOf("-o", elf.absolutePath)
            val linkResult = runProcess(linkCommand, workDir, environment)
            output.append("$ ${linkCommand.joinToString(" ")}\n").append(linkResult.output)
            if (linkResult.exitCode != 0) {
                return AvrCompileResult.Failure(output.toString(), parseDiagnostics(output.toString()), nativeEngine.version())
            }

            val hex = File(workDir, "${safeName(request.projectName)}.hex")
            val hexCommand = listOf(objcopy.absolutePath, "-O", " ihex".trim(), "-R", ".eeprom", elf.absolutePath, hex.absolutePath)
            val hexResult = runProcess(hexCommand, workDir, environment)
            output.append("$ ${hexCommand.joinToString(" ")}\n").append(hexResult.output)
            if (hexResult.exitCode != 0 || !hex.isFile || hex.length() == 0L) {
                return AvrCompileResult.Failure(output.toString(), parseDiagnostics(output.toString()), nativeEngine.version())
            }
            val persistentHex = File(appContext.cacheDir, "avr_uploads/${System.currentTimeMillis()}_${hex.name}").apply {
                parentFile?.mkdirs()
                writeBytes(hex.readBytes())
            }
            val sizeCommand = listOf(sizeTool.absolutePath, "-A", elf.absolutePath)
            val sizeResult = runProcess(sizeCommand, workDir, environment)
            output.append("$ ${sizeCommand.joinToString(" ")}\n").append(sizeResult.output)
            val memoryUsage = if (sizeResult.exitCode == 0) parseMemoryUsage(sizeResult.output) else null
            AvrCompileResult.Success(
                output = output.toString() + "HEX: ${persistentHex.absolutePath}\n",
                nativeEngineVersion = nativeEngine.version(),
                hexFilePath = persistentHex.absolutePath,
                memoryUsage = memoryUsage
            )
        } catch (error: Exception) {
            failure(error.message ?: "AVR process failed", nativeScan)
        } finally {
            workDir.deleteRecursively()
        }
    }

    private fun linkerScriptName(board: AvrBoard): String = when (board.mcu) {
        "atmega2560" -> "avr6.xn"
        "atmega1280" -> "avr51.xn"
        else -> "avr5.xn"
    }

    private fun commonArguments(
        request: AvrCompileRequest,
        toolchainRoot: File,
        deviceSpecs: File,
        includeDirectories: List<File>
    ): MutableList<String> = mutableListOf<String>().apply {
        addAll(listOf(
            "-specs=${deviceSpecs.absolutePath}",
            "-mmcu=${request.board.mcu}",
            "-DF_CPU=${request.board.cpuHz}L",
            "-DARDUINO=10819",
            "-DARDUINO_ARCH_AVR",
            "-B", File(toolchainRoot, "bin").absolutePath + File.separator,
            "-B", File(toolchainRoot, "libexec/gcc/avr/7.3.0").absolutePath + File.separator,
            "-L", File(toolchainRoot, "avr/lib").absolutePath,
            "-L", File(toolchainRoot, "lib/gcc/avr/7.3.0").absolutePath
        ))
        includeDirectories.forEach { addAll(listOf("-I", it.absolutePath)) }
    }

    private fun buildEnvironment(toolchainRoot: File): Map<String, String> {
        val binPath = File(toolchainRoot, "bin").absolutePath
        val libexecPath = File(toolchainRoot, "libexec/gcc/avr/7.3.0").absolutePath
        return mapOf(
            "PATH" to listOf(binPath, libexecPath, System.getenv("PATH").orEmpty()).filter { it.isNotBlank() }.joinToString(File.pathSeparator),
            "GCC_EXEC_PREFIX" to File(toolchainRoot, "lib/gcc").absolutePath + File.separator,
            "COMPILER_PATH" to listOf(binPath, libexecPath).joinToString(File.pathSeparator),
            "LIBRARY_PATH" to listOf(
                File(toolchainRoot, "lib/gcc/avr/7.3.0").absolutePath,
                File(toolchainRoot, "avr/lib").absolutePath,
                binPath
            ).joinToString(File.pathSeparator)
        )
    }

    private data class ProcessResult(val exitCode: Int, val output: String)

    private fun runProcess(command: List<String>, workDir: File, environment: Map<String, String>): ProcessResult {
        val processBuilder = ProcessBuilder(command).directory(workDir).redirectErrorStream(true)
        processBuilder.environment().putAll(environment)
        val process = processBuilder.start()
        process.outputStream.close()
        val outputBuffer = StringBuilder()
        val outputThread = Thread {
            process.inputStream.bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    synchronized(outputBuffer) {
                        if (outputBuffer.length < MAX_OUTPUT_CHARS) outputBuffer.append(line).append('\n')
                    }
                }
            }
        }.apply { isDaemon = true; start() }
        val finished = process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly()
            outputThread.join(1_000)
            return ProcessResult(124, outputBuffer.toString() + "Process timeout after $TIMEOUT_SECONDS seconds\n")
        }
        outputThread.join(1_000)
        return ProcessResult(process.exitValue(), synchronized(outputBuffer) { outputBuffer.toString() })
    }

    private fun failure(output: String, nativeScan: String): AvrCompileResult.Failure {
        val nativeDiagnostic = parseNativeScan(nativeScan)
        return AvrCompileResult.Failure(output, nativeDiagnostic + parseDiagnostics(output), nativeEngine.version())
    }

    private fun buildSource(code: String): String {
        val header = if (code.contains("Arduino.h")) "" else "#include <Arduino.h>\n"
        return header + code + "\n"
    }

    private fun parseDiagnostics(output: String): List<AvrDiagnostic> = output.lineSequence().mapNotNull { line ->
        val match = diagnosticPattern.matcher(line.trim())
        if (!match.matches()) return@mapNotNull null
        val severity = when (match.group(4).orEmpty()) {
            "warning" -> AvrDiagnostic.Severity.WARNING
            "note" -> AvrDiagnostic.Severity.NOTE
            else -> AvrDiagnostic.Severity.ERROR
        }
        AvrDiagnostic(match.group(2)?.toIntOrNull() ?: 1, match.group(3)?.toIntOrNull() ?: 1, severity, match.group(5).orEmpty())
    }.toList()

    private fun parseMemoryUsage(output: String): AvrMemoryUsage? {
        val sections = linkedMapOf<String, Int>()
        val pattern = Regex("^(\\.[A-Za-z0-9_.]+)\\s+(\\d+)\\s+.*$")
        output.lineSequence().forEach { line ->
            val match = pattern.matchEntire(line.trim()) ?: return@forEach
            sections[match.groupValues[1]] = match.groupValues[2].toIntOrNull() ?: 0
        }
        if (sections.isEmpty()) return null
        val flash = (sections[".text"] ?: 0) + (sections[".data"] ?: 0)
        val sram = (sections[".data"] ?: 0) + (sections[".bss"] ?: 0) + (sections[".noinit"] ?: 0)
        return AvrMemoryUsage(flashBytes = flash, sramBytes = sram, sections = sections)
    }

    private fun discoverLibraryIncludeDirectories(root: File): List<File> = root.walkTopDown()
        .take(MAX_LIBRARY_DIRECTORIES)
        .filter { it.isDirectory && !isExcludedLibraryPath(root, it) }
        .toList()

    private fun discoverLibrarySources(root: File): List<File> = root.walkTopDown()
        .take(MAX_LIBRARY_SOURCES)
        .filter { file -> file.isFile && !isExcludedLibraryPath(root, file) && file.extension.lowercase() in setOf("c", "cc", "cpp") }
        .toList()

    private fun isExcludedLibraryPath(root: File, file: File): Boolean {
        val relative = runCatching { file.relativeTo(root).path }.getOrDefault("")
        return relative.split(File.separatorChar).any {
            it.equals("examples", true) || it.equals("example", true) || it.equals("test", true) ||
                it.equals("tests", true) || it.equals("extras", true) || it == ".git"
        }
    }

    private fun parseNativeScan(value: String): List<AvrDiagnostic> {
        val pieces = value.split('|', limit = 4)
        if (pieces.firstOrNull() != "ERROR") return emptyList()
        return listOf(AvrDiagnostic(pieces.getOrNull(1)?.toIntOrNull() ?: 1, pieces.getOrNull(2)?.toIntOrNull() ?: 1, AvrDiagnostic.Severity.ERROR, pieces.getOrNull(3).orEmpty()))
    }

    private fun safeName(value: String): String = value.replace(Regex("[^A-Za-z0-9_]+"), "_").trim('_').ifBlank { "sketch" }

    companion object {
        private const val MAX_LIBRARY_DIRECTORIES = 256
        private const val MAX_LIBRARY_SOURCES = 128
        private const val TIMEOUT_SECONDS = 45L
        private const val MAX_OUTPUT_CHARS = 1_000_000
    }
}
