package com.stormer.glassplayer

import android.media.audiofx.*
import android.util.Log

/**
 * All effect calls are wrapped in runCatching so a device rejecting an effect
 * or a value can never crash the app — the effect just stays inactive.
 * EQ band levels are clamped to the device's real reported range.
 */
class AudioEffectsManager(private val audioSessionId: Int) {

    companion object { private const val TAG = "AudioFx" }

    var loudnessEnhancer: LoudnessEnhancer? = null
    var equalizer: Equalizer? = null
    var bassBoost: BassBoost? = null
    var virtualizer: Virtualizer? = null
    var presetReverb: PresetReverb? = null

    var boostGain: Int = 500
    var bassStrength: Int = 0
    var virtualizerStrength: Int = 0
    var reverbPreset: Short = PresetReverb.PRESET_NONE
    var eqBands: IntArray = IntArray(5) { 0 }
    var vocalsBoostActive: Boolean = false

    private var eqMin = -1500
    private var eqMax = 1500
    private var eqNumBands = 5

    fun init() {
        loudnessEnhancer = safe("LoudnessEnhancer") { LoudnessEnhancer(audioSessionId).apply { enabled = true; setTargetGain(boostGain) } }
        equalizer = safe("Equalizer") {
            Equalizer(0, audioSessionId).apply {
                enabled = true
                eqNumBands = numberOfBands.toInt()
                val r = bandLevelRange
                eqMin = r[0].toInt(); eqMax = r[1].toInt()
                eqBands = IntArray(eqNumBands) { 0 }
            }
        }
        bassBoost = safe("BassBoost") { BassBoost(0, audioSessionId).apply { enabled = true } }
        virtualizer = safe("Virtualizer") { Virtualizer(0, audioSessionId).apply { enabled = true } }
        presetReverb = safe("PresetReverb") { PresetReverb(0, audioSessionId).apply { enabled = false } }
    }

    private fun <T> safe(name: String, block: () -> T): T? = try { block() } catch (e: Throwable) {
        Log.w(TAG, "create $name failed: ${e.message}"); null
    }

    fun setBoost(gain: Int) = runCatching {
        boostGain = gain
        loudnessEnhancer?.setTargetGain(gain.coerceIn(0, 2000))
    }.onFailure { Log.w(TAG, "setBoost: ${it.message}") }.let {}

    fun setBass(strength: Int) = runCatching {
        bassStrength = strength
        bassBoost?.takeIf { it.strengthSupported }?.setStrength(strength.coerceIn(0, 1000).toShort())
    }.onFailure { Log.w(TAG, "setBass: ${it.message}") }.let {}

    fun setVirtualizer(strength: Int) = runCatching {
        virtualizerStrength = strength
        virtualizer?.takeIf { it.strengthSupported }?.setStrength(strength.coerceIn(0, 1000).toShort())
    }.onFailure { Log.w(TAG, "setVirtualizer: ${it.message}") }.let {}

    fun setReverb(preset: Short) = runCatching {
        reverbPreset = preset
        presetReverb?.apply {
            if (preset == PresetReverb.PRESET_NONE) enabled = false
            else { this.preset = preset; enabled = true }
        }
    }.onFailure { Log.w(TAG, "setReverb: ${it.message}") }.let {}

    fun setEqBand(band: Int, levelMb: Int) = runCatching {
        if (band < eqBands.size) eqBands[band] = levelMb
        equalizer?.setBandLevel(band.toShort(), levelMb.coerceIn(eqMin, eqMax).toShort())
    }.onFailure { Log.w(TAG, "setEqBand: ${it.message}") }.let {}

    fun applyVocalBoost(active: Boolean) = runCatching {
        vocalsBoostActive = active
        val eq = equalizer ?: return@runCatching
        if (active) {
            if (eqNumBands > 2) eq.setBandLevel(2, (eqBands.getOrElse(2){0} + 600).coerceIn(eqMin, eqMax).toShort())
            if (eqNumBands > 3) eq.setBandLevel(3, (eqBands.getOrElse(3){0} + 400).coerceIn(eqMin, eqMax).toShort())
        } else {
            if (eqNumBands > 2) eq.setBandLevel(2, eqBands.getOrElse(2){0}.coerceIn(eqMin, eqMax).toShort())
            if (eqNumBands > 3) eq.setBandLevel(3, eqBands.getOrElse(3){0}.coerceIn(eqMin, eqMax).toShort())
        }
    }.onFailure { Log.w(TAG, "vocalBoost: ${it.message}") }.let {}

    fun applyPreset(preset: EqPreset) = runCatching {
        val eq = equalizer ?: return@runCatching
        val n = minOf(eqNumBands, preset.levels.size)
        for (i in 0 until n) {
            eqBands[i] = preset.levels[i]
            eq.setBandLevel(i.toShort(), preset.levels[i].coerceIn(eqMin, eqMax).toShort())
        }
    }.onFailure { Log.w(TAG, "applyPreset: ${it.message}") }.let {}

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
