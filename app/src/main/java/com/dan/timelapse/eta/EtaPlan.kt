package com.dan.timelapse.eta

/**
 * Builds the phase plan for each long-running path.
 *
 * The units used here are the contract with [EtaHeuristics]: a rate can only be
 * learned if every job of a phase measures the same thing. Keeping the
 * definitions in one place is what stops the "frames predicted from the whole
 * clip but measured on the selected frames" class of bug.
 */
object EtaSpecs {

    /**
     * Video render, including the post-loop work that the progress bar never
     * shows: timestamp retime, audio mux and the gallery write.
     *
     * @param deliveredFrames frames the render loop will actually consume
     *        (trim range / input-speed stride), i.e. the number in the dialog.
     */
    fun videoRender(
        signature: EtaSignature,
        deliveredFrames: Int,
        outWidth: Int,
        outHeight: Int,
        effect: String,
        stackWindow: Int,
        stabilized: Boolean,
        hasAudioMux: Boolean,
        temperatureC: Int
    ): EtaJobSpec {
        val frames = deliveredFrames.coerceAtLeast(1)
        val phases = ArrayList<EtaPhasePlan>(5)
        if (stabilized) {
            phases.add(EtaPhasePlan(EtaPhase.ANALYZE, EtaUnits.analysisUnits(frames, outWidth, outHeight)))
        }
        phases.add(
            EtaPhasePlan(
                EtaPhase.RENDER,
                EtaUnits.videoRenderUnits(frames, outWidth, outHeight, effect, stackWindow)
            )
        )
        phases.add(EtaPhasePlan(EtaPhase.RETIME, frames.toDouble()))
        if (hasAudioMux) phases.add(EtaPhasePlan(EtaPhase.MUX, 1.0))
        phases.add(EtaPhasePlan(EtaPhase.SAVE, 1.0))
        return EtaJobSpec(signature, phases, tailMs = 0.0, temperatureC = temperatureC)
    }

    /**
     * Multi-frame super resolution. [selectedFrames] can be an estimate at the
     * start (the processor refines it with [EtaJob.planUnits] once alignment has
     * picked the real set).
     */
    fun mfsrRender(
        signature: EtaSignature,
        scanFrames: Int,
        selectedFrames: Int,
        inputWidth: Int,
        inputHeight: Int,
        outputWidth: Int,
        outputHeight: Int,
        temperatureC: Int
    ): EtaJobSpec {
        val scan = scanFrames.coerceAtLeast(1)
        val selected = selectedFrames.coerceIn(1, scan)
        val inputMp = EtaUnits.megapixels(inputWidth, inputHeight).coerceAtLeast(0.01)
        val outputMp = EtaUnits.megapixels(outputWidth, outputHeight).coerceAtLeast(0.01)
        return EtaJobSpec(
            signature = signature,
            phases = listOf(
                EtaPhasePlan(EtaPhase.SCAN, scan * inputMp),
                EtaPhasePlan(EtaPhase.ALIGN, selected * inputMp),
                EtaPhasePlan(EtaPhase.FUSE, selected * (inputMp + outputMp)),
                EtaPhasePlan(EtaPhase.FINALIZE, outputMp),
                EtaPhasePlan(EtaPhase.SAVE, outputMp)
            ),
            temperatureC = temperatureC
        )
    }

    /** Single still exported at full resolution. */
    fun stillRender(
        signature: EtaSignature,
        outWidth: Int,
        outHeight: Int,
        temperatureC: Int
    ): EtaJobSpec {
        val mp = EtaUnits.stillUnits(outWidth, outHeight)
        return EtaJobSpec(
            signature = signature,
            phases = listOf(
                EtaPhasePlan(EtaPhase.RENDER, mp),
                EtaPhasePlan(EtaPhase.SAVE, 1.0)
            ),
            temperatureC = temperatureC
        )
    }

    /** Folder scan / image batch / share materialisation. Units are items. */
    fun loadMedia(
        signature: EtaSignature,
        items: Int,
        temperatureC: Int,
        phase: EtaPhase = EtaPhase.LOAD
    ): EtaJobSpec = EtaJobSpec(
        signature = signature,
        phases = listOf(EtaPhasePlan(phase, items.coerceAtLeast(1).toDouble())),
        temperatureC = temperatureC
    )
}
