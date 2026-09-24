package com.example.avrandroid

import android.content.Context
import android.os.Build
import android.system.Os
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/** Installs the ABI-specific Android AVR toolchain from APK assets on first use. */
internal object AvrToolchainInstaller {
    private const val ASSET_ROOT = "toolchain"
    private const val INSTALL_MARKER = ".luma-toolchain-installed"
    private const val BUFFER_SIZE = 64 * 1024
    private val lock = Any()

    data class Result(val installed: Boolean, val root: File, val message: String)

    fun ensureInstalled(context: Context): Result {
        val root = File(context.filesDir, "toolchain")
        val abi = selectAbi()
            ?: return Result(false, root, "لا توجد حزمة toolchain مضمّنة لمعمارية الجهاز")
        val marker = File(root, INSTALL_MARKER)
        val nativeRoot = File(context.applicationInfo.nativeLibraryDir)
        val expectedMarker = "abi=$abi\nversion=arduino-avr-1.8.8-gcc-7.3.0-atmel3.6.1-arduino7-target-sync-v6\n"

        return synchronized(lock) {
            if (marker.isFile && marker.readText() == expectedMarker && isUsable(root)) {
                return try {
                    createNativeLinks(root, nativeRoot)
                    createBinutilsAliases(root)
                    verifyBinutilsAliases(root)
                    Result(true, root, "toolchain مثبتة مسبقاً لمعمارية $abi")
                } catch (error: Exception) {
                    Result(false, root, "تعذر ربط executables native: ${error.message}")
                }
            }

            val temporary = File(context.filesDir, "toolchain.installing")
            temporary.deleteRecursively()
            try {
                extractTree(context, "$ASSET_ROOT/$abi", temporary)
                if (!isUsable(temporary)) {
                    temporary.deleteRecursively()
                    return Result(false, root, "ملفات toolchain المستخرجة غير مكتملة لمعمارية $abi")
                }
                makeExecutables(temporary)
                root.deleteRecursively()
                if (!temporary.renameTo(root)) {
                    temporary.deleteRecursively()
                    return Result(false, root, "تعذر تثبيت toolchain داخل ${root.absolutePath}")
                }
                createNativeLinks(root, nativeRoot)
                createBinutilsAliases(root)
                verifyBinutilsAliases(root)
                File(root, INSTALL_MARKER).writeText(expectedMarker)
                Result(true, root, "تم تثبيت toolchain لمعمارية $abi من nativeLibraryDir")
            } catch (error: Exception) {
                temporary.deleteRecursively()
                Result(false, root, "فشل تثبيت toolchain: ${error.message ?: error.javaClass.simpleName}")
            }
        }
    }

    private fun selectAbi(): String? {
        val supported = Build.SUPPORTED_ABIS.toSet()
        return listOf("arm64-v8a", "armeabi-v7a").firstOrNull { it in supported }
    }

    private fun isUsable(root: File): Boolean {
        return File(root, "bin/avr-g++").isFile &&
            File(root, "libexec/gcc/avr/7.3.0/cc1plus").isFile &&
            File(root, "avr/include/avr/io.h").isFile &&
            File(root, "avr-core/cores/arduino/Arduino.h").isFile &&
            File(root, "avr-core/variants/standard/pins_arduino.h").isFile &&
            File(root, "avr/lib/ldscripts/avr5.xn").isFile &&
            File(root, "lib/gcc/avr/7.3.0/device-specs/specs-atmega328p").isFile
    }

    private fun extractTree(context: Context, assetPath: String, destination: File) {
        destination.mkdirs()
        val children = context.assets.list(assetPath).orEmpty()
        if (children.isEmpty()) {
            copyFile(context, assetPath, destination)
            return
        }
        for (child in children) {
            val source = "$assetPath/$child"
            val target = File(destination, child)
            val nested = context.assets.list(source).orEmpty()
            if (nested.isEmpty()) {
                copyFile(context, source, target)
            } else {
                extractTree(context, source, target)
            }
        }
    }

    private fun copyFile(context: Context, source: String, target: File) {
        target.parentFile?.mkdirs()
        context.assets.open(source).use { input ->
            FileOutputStream(target).use { output ->
                val buffer = ByteArray(BUFFER_SIZE)
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    output.write(buffer, 0, read)
                }
                output.fd.sync()
            }
        }
    }

    private fun createNativeLinks(root: File, nativeRoot: File) {
        if (!nativeRoot.isDirectory) {
            throw IOException("nativeLibraryDir غير موجود: ${nativeRoot.absolutePath}")
        }
        for ((relative, nativeName) in NATIVE_EXECUTABLES) {
            val nativeExecutable = File(nativeRoot, nativeName)
            if (!nativeExecutable.isFile) {
                throw IOException("ملف native غير موجود: ${nativeExecutable.absolutePath}")
            }
            val link = File(root, relative)
            link.parentFile?.mkdirs()
            link.delete()
            Os.symlink(nativeExecutable.absolutePath, link.absolutePath)
        }
    }

    private fun createBinutilsAliases(root: File) {
        val aliases = linkedMapOf(
            "as" to "avr-as",
            "ld" to "avr-ld",
            "ar" to "avr-ar",
            "ranlib" to "avr-ranlib",
            "objcopy" to "avr-objcopy",
            "objdump" to "avr-objdump",
            "nm" to "avr-nm",
            "size" to "avr-size",
            "strip" to "avr-strip"
        )
        val bin = File(root, "bin").apply { mkdirs() }
        aliases.forEach { (alias, target) ->
            val targetFile = File(bin, target)
            if (!targetFile.exists()) throw IOException("binutils target missing: ${targetFile.absolutePath}")
            val aliasFile = File(bin, alias)
            aliasFile.delete()
            Os.symlink(target, aliasFile.absolutePath)
        }
    }

    private fun verifyBinutilsAliases(root: File) {
        listOf("as", "ld", "ar", "ranlib", "objcopy", "objdump", "nm", "size", "strip").forEach { alias ->
            val file = File(root, "bin/$alias")
            if (!file.isFile) throw IOException("binutils alias غير صالح: ${file.absolutePath}")
        }
    }

    private fun makeExecutables(root: File) {
        listOf(File(root, "bin"), File(root, "libexec")).forEach { directory ->
            directory.walkTopDown()
                .filter { it.isFile }
                .forEach { file ->
                    if (!file.setExecutable(true, false) && !file.canExecute()) {
                        throw IOException("تعذر جعل الملف قابلاً للتنفيذ: ${file.absolutePath}")
                    }
                }
        }
    }

    private val NATIVE_EXECUTABLES = linkedMapOf(
            "bin/avr-g++" to "libluma_avr_gpp.so",
            "bin/avrdude" to "libluma_avrdude.so",
        "bin/avr-gcc" to "libluma_avr_gcc.so",
        "bin/avr-cpp" to "libluma_avr_cpp.so",
        "bin/avr-as" to "libluma_avr_as.so",
        "bin/avr-ld" to "libluma_avr_ld.so",
        "bin/avr-ar" to "libluma_avr_ar.so",
        "bin/avr-ranlib" to "libluma_avr_ranlib.so",
        "bin/avr-objcopy" to "libluma_avr_objcopy.so",
        "bin/avr-objdump" to "libluma_avr_objdump.so",
        "bin/avr-size" to "libluma_avr_size.so",
        "bin/avr-nm" to "libluma_avr_nm.so",
        "bin/avr-readelf" to "libluma_avr_readelf.so",
        "bin/avr-strip" to "libluma_avr_strip.so",
        "bin/avr-addr2line" to "libluma_avr_addr2line.so",
        "bin/avr-c++filt" to "libluma_avr_cppfilt.so",
        "libexec/gcc/avr/7.3.0/cc1" to "libluma_avr_cc1.so",
        "libexec/gcc/avr/7.3.0/cc1plus" to "libluma_avr_cc1plus.so",
        "libexec/gcc/avr/7.3.0/collect2" to "libluma_avr_collect2.so",
        "libexec/gcc/avr/7.3.0/lto1" to "libluma_avr_lto1.so",
        "libexec/gcc/avr/7.3.0/lto-wrapper" to "libluma_avr_lto_wrapper.so"
    )
}
