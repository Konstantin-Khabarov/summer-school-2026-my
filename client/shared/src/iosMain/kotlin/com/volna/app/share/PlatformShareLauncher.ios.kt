package com.volna.app.share

actual object PlatformShareLauncher : ShareLauncher {
    actual override fun shareText(text: String) {
        // Не реализовано: шаринг слота ограничен Android (см. FEATURE_share-slot.md).
    }
}
