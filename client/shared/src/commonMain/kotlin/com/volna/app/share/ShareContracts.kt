package com.volna.app.share

interface ShareLauncher {
    fun shareText(text: String)
}

expect object PlatformShareLauncher : ShareLauncher {
    override fun shareText(text: String)
}
