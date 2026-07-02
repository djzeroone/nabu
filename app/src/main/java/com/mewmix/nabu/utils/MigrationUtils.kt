package com.mewmix.nabu.utils

import android.content.Context
import java.io.File

object MigrationUtils {
    fun migrateLegacyKokoro(context: Context) {
        val legacyDir = File(context.filesDir, "models/kokoro")
        if (legacyDir.exists() && legacyDir.isDirectory) {
            val fp16File = File(legacyDir, "model_fp16.onnx")
            if (fp16File.exists()) {
                val dest = File(context.filesDir, "models/kokoro-fp16")
                dest.mkdirs()
                fp16File.renameTo(File(dest, "model_fp16.onnx"))
            }
            val int8File = File(legacyDir, "model_int8.onnx")
            if (int8File.exists()) {
                val dest = File(context.filesDir, "models/kokoro-int8")
                dest.mkdirs()
                int8File.renameTo(File(dest, "model_int8.onnx"))
            }
            // Delete legacy dir if empty
            legacyDir.listFiles()?.let { if (it.isEmpty()) legacyDir.delete() }
        }
    }
}
