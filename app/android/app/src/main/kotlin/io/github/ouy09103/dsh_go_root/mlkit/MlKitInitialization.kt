package io.github.ouy09103.dsh_go_root.mlkit

/** Shared by barcode scanning and OCR; construction does not initialize the SDK. */
internal class MlKitInitialization {
    private var attempted = false
    private var succeeded = false

    @Synchronized
    fun ensureInitialized(initialize: () -> Unit) {
        if (succeeded) return
        // A failed SDK initialization can leave partial global state. Do not retry it.
        check(!attempted) { "ML Kit initialization unavailable" }
        attempted = true
        initialize()
        succeeded = true
    }
}
