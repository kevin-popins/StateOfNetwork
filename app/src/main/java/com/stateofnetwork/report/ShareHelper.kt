package com.stateofnetwork.report

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File

object ShareHelper {

    fun shareBytes(ctx: Context, fileName: String, mime: String, bytes: ByteArray) {
        val file = File(ctx.cacheDir, fileName)
        file.writeBytes(bytes)

        val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        ctx.startActivity(Intent.createChooser(intent, "Поделиться отчётом"))
    }
}
