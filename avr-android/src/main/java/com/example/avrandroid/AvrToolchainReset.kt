package com.example.avrandroid

import android.content.Context
import java.io.File

object AvrToolchainReset {
    fun resetAndReload(context: Context): Result {
        val appContext = context.applicationContext
        val root = File(appContext.filesDir, "toolchain")
        val installing = File(appContext.filesDir, "toolchain.installing")
        return try {
            root.deleteRecursively()
            installing.deleteRecursively()
            val installation = AvrToolchainInstaller.ensureInstalled(appContext)
            if (installation.installed) {
                Result(true, "تم حذف الأدوات القديمة وإعادة تحميل AVR-GCC وavrdude بنجاح")
            } else {
                Result(false, installation.message)
            }
        } catch (error: Exception) {
            Result(false, error.message ?: error.javaClass.simpleName)
        }
    }

    data class Result(val success: Boolean, val message: String)
}
