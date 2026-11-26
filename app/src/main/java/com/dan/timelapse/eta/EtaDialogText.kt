package com.dan.timelapse.eta

/**
 * The exact strings the processing dialogs show.
 *
 * Kept out of the Activity so the user-visible text is unit-tested: the title is
 * the only place the estimate reaches the user, and a regression there (a raw
 * millisecond value, a missing prefix, "0s left" glued onto a status line) is
 * invisible to every other test.
 */
object EtaDialogText {

    /**
     * Which phase a loading-dialog status line belongs to.
     *
     * Several distinct passes report into the same dialog and they have very
     * different per-item costs (an EXIF probe is ~1 ms, a full decode ~30 ms, a
     * shared-file copy ~20 ms), so the estimate must not assume one. Kept here
     * rather than inline in the Activity so it is unit-tested against the exact
     * strings the loaders emit.
     */
    fun loadingPhaseFor(status: String): EtaPhase = when {
        status.startsWith("Preparing", ignoreCase = true) -> EtaPhase.INDEX
        // Header-only read: 20-30x cheaper than a decode. Sharing LOAD's prior
        // made a 0.1 s probe of 120 images claim "~5s left".
        status.startsWith("Probing", ignoreCase = true) -> EtaPhase.PROBE
        status.startsWith("Verifying", ignoreCase = true) -> EtaPhase.LOAD
        status.startsWith("Copying", ignoreCase = true) -> EtaPhase.LOAD
        status.startsWith("Listing", ignoreCase = true) -> EtaPhase.FILTER
        else -> EtaPhase.FILTER
    }

    /** Dialog title, e.g. `Rendering (~2m 30s)`, or just [prefix] while the first
     * estimate is still being computed.
     */
    fun title(prefix: String, formattedEta: String?): String =
        if (formattedEta.isNullOrEmpty()) prefix else "$prefix ($formattedEta)"

    /** Title straight from milliseconds, e.g. the full-res and loading dialogs. */
    fun titleForMs(prefix: String, remainingMs: Long): String =
        title(prefix, EtaFormat.format(remainingMs))

    /** Status line with the estimate appended, e.g. `Scanning frames: 40/120 · ~12s left`. */
    fun statusWithEta(status: String, remainingMs: Long): String {
        val suffix = EtaFormat.formatSuffix(remainingMs)
        return if (suffix.isEmpty()) status else "$status  ·  $suffix"
    }
}
