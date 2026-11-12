package io.github.ouy09103.dsh_go_root.shortcuts

import java.io.File

/** MainActivity is exported: validate both the internal origin and the private file boundary. */
internal object ScreenshotEntry {
    fun event(cacheDir: File, token: String, receivedToken: String?, path: String?): Map<String, String>? {
        if (receivedToken != token) return null
        val file = ScreenshotFiles.validate(cacheDir, path) ?: return null
        return mapOf("kind" to "screenshot", "path" to file.path)
    }
}
