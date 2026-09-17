package io.ucc.app.ui.scan

/**
 * Outcome of decoding a still image picked from the gallery. Pure Kotlin so the
 * selection rules are unit-tested; ML Kit only produces the raw list of values.
 */
sealed interface QrImageResult {
    /** One payload to hand to the existing import pipeline (never logged: may contain credentials). */
    data class Found(val payload: String, val totalCodes: Int) : QrImageResult
    /** Image decoded fine but contains no QR code (or only empty ones). */
    data object NoQr : QrImageResult
    /** The picked item is not a decodable bitmap (corrupt file, unsupported format, unreadable stream). */
    data object InvalidImage : QrImageResult

    companion object {
        /**
         * Picks the payload from all QR values found in one image.
         * Several codes: they are joined with newlines, because the import
         * pipeline already accepts "one link per line" and reports per-line
         * results (duplicates, unsupported, malformed) in the preview.
         */
        fun fromRawValues(values: List<String?>): QrImageResult {
            val nonBlank = values.filterNotNull().map { it.trim() }.filter { it.isNotEmpty() }.distinct()
            return if (nonBlank.isEmpty()) NoQr else Found(nonBlank.joinToString("\n"), nonBlank.size)
        }
    }
}
