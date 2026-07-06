package com.volna.app.share

import android.content.Context
import android.content.Intent
import com.volna.app.core.logging.AppLogger

actual object PlatformShareLauncher : ShareLauncher {
    private var context: Context? = null

    fun initialize(context: Context) {
        this.context = context.applicationContext
    }

    actual override fun shareText(text: String) {
        val appContext = context ?: return
        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        val chooser = Intent.createChooser(sendIntent, null).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { appContext.startActivity(chooser) }
            .onFailure { failure -> AppLogger.e(failure, "Failed to open share sheet") }
    }
}
