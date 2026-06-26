package com.stormer.glassplayer

import android.media.audiofx.*

class AudioEffectsManager(private val audioSessionId: Int) {

    var loudnessEnhancer: LoudnessEnhancer? = null
    var equalizer: Equalizer? = null
    var bassBoost: BassBoost? = null
    var virtualizer: Virtualizer? = null
    var presetReverb: PresetReverb? = null

    // State
    var boostGain: Int = 500
    var bassStrength: Int = 500
    var virtualizerStrength: Int = 500
    var reverbPreset: Short = PresetReverb.PRESET_NONE
    var eqBands: IntArray = IntArray(5) { 0 }
    var vocalsBoostActive: Boolean = false

    fun init() {
        runCatching { loudnessEnhancer = LoudnessEnhancer(audioSessionId).apply { enabled = true; setTargetGain(boostGain) } }
        runCatching {
            equalizer = Equalizer(0, audioSessionId).apply {
                enabled = true
                val numBands = numberOfBands.toInt()
                eqBands = IntArray(numBands) { 0 }
            }
        }
        runCatching { bassBoost = BassBoost(0, audioSessionId).apply { enabled = true; setStrength(bassStrength.toShort()) } }
        runCatching { virtualizer = Virtualizer(0, audioSessionId).apply { enabled = true; setStrength(virtualizerStrength.toShort()) } }
        runCatching { presetReverb = PresetReverb(0, audioSessionId).apply { enabled = false; preset = PresetReverb.PRESET_NONE } }
    }

    fun setBoost(gain: Int) {
        boostGain = gain
        loudnessEnhancer?.setTargetGain(gain)
    }

    fun setBass(strength: Int) {
        bassStrength = strength
        bassBoost?.setStrength(strength.toShort())
    }

    fun setVirtualizer(strength: Int) {
        virtualizerStrength = strength
        virtualizer?.setStrength(strength.toShort())
    }

    fun setReverb(preset: Short) {
        reverbPreset = preset
        presetReverb?.apply {
            if (preset == PresetReverb.PRESET_NONE) {
                enabled = false
            } else {
                enabled = true
                this.preset = preset
            }
        }
    }

    fun setEqBand(band: Int, levelMb: Int) {
        eqBands[band] = levelMb
        equalizer?.setBandLevel(band.toShort(), levelMb.toShort())
    }

    fun applyVocalBoost(active: Boolean) {
        vocalsBoostActive = active
        equalizer?.let { eq ->
            val numBands = eq.numberOfBands.toInt()
            // Vocal range: bands 2 and 3 (approx 910Hz and 3.6kHz)
            if (active) {
                if (numBands > 2) eq.setBandLevel(2, (eqBands.getOrElse(2) { 0 } + 600).coerceIn(-1500, 1500).toShort())
                if (numBands > 3) eq.setBandLevel(3, (eqBands.getOrElse(3) { 0 } + 400).coerceIn(-1500, 1500).toShort())
            } else {
                if (numBands > 2) eq.setBandLevel(2, eqBands.getOrElse(2) { 0 }.toShort())
                if (numBands > 3) eq.setBandLevel(3, eqBands.getOrElse(3) { 0 }.toShort())
            }
        }
    }

    fun applyPreset(preset: EqPreset) {
        val levels = preset.levels
        equalizer?.let { eq ->
            val numBands = minOf(eq.numberOfBands.toInt(), levels.size)
            for (i in 0 until numBands) {
                eqBands[i] = levels[i]
                eq.setBandLevel(i.toShort(), levels[i].toShort())
            }
        }
    }

    fun getEqBandFrequencies(): List<String> {
        val eq = equalizer ?: return listOf("60Hz", "230Hz", "910Hz", "3.6kHz", "14kHz")
        return (0 until eq.numberOfBands.toInt()).map { band ->
            val hz = eq.getCenterFreq(band.toShort()) / 1000
            if (hz >= 1000) "${hz / 1000}kHz" else "${hz}Hz"
        }
    }

    fun release() {
        runCatching { loudnessEnhancer?.release() }
        runCatching { equalizer?.release() }
        runCatching { bassBoost?.release() }
        runCatching { virtualizer?.release() }
        runCatching { presetReverb?.release() }
    }
}

enum class EqPreset(val label: String, val levels: IntArray) {
    FLAT("Flat", intArrayOf(0, 0, 0, 0, 0)),
    MOVIE("Movie", intArrayOf(300, 100, 0, 200, 400)),
    DIALOGUE("Dialogue", intArrayOf(-200, 0, 600, 500, 0)),
    BASS_HEAVY("Bass Heavy", intArrayOf(800, 600, 0, -200, -300)),
    TREBLE_BOOST("Treble Boost", intArrayOf(-300, -100, 0, 500, 800)),
    ROCK("Rock", intArrayOf(500, 200, -100, 300, 500)),
    JAZZ("Jazz", intArrayOf(300, 0, 200, 300, 500)),
    CLASSICAL("Classical", intArrayOf(500, 300, -200, 200, 500)),
    VOCAL("Vocal", intArrayOf(-200, 0, 700, 600, 0)),
    NIGHT_MODE("Night Mode", intArrayOf(-500, -200, 400, 300, -400));
}

object ReverbPresets {
    val list = listOf(
        "None" to PresetReverb.PRESET_NONE,
        "Small Room" to PresetReverb.PRESET_SMALLROOM,
        "Medium Room" to PresetReverb.PRESET_MEDIUMROOM,
        "Large Room" to PresetReverb.PRESET_LARGEROOM,
        "Medium Hall" to PresetReverb.PRESET_MEDIUMHALL,
        "Large Hall" to PresetReverb.PRESET_LARGEHALL,
        "Plate" to PresetReverb.PRESET_PLATE
    )
}
