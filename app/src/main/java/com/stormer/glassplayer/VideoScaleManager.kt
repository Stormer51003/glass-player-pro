package com.stormer.glassplayer

import android.view.SurfaceView
import android.view.TextureView
import androidx.media3.common.VideoSize
import androidx.media3.ui.AspectRatioFrameLayout

enum class ZoomMode(val label: String, val resizeMode: Int) {
    FIT("Fit", AspectRatioFrameLayout.RESIZE_MODE_FIT),
    FILL("Fill", AspectRatioFrameLayout.RESIZE_MODE_ZOOM),
    STRETCH("Stretch", AspectRatioFrameLayout.RESIZE_MODE_FILL),
    FIXED_WIDTH("Fixed Width", AspectRatioFrameLayout.RESIZE_MODE_FIXED_WIDTH),
    FIXED_HEIGHT("Fixed Height", AspectRatioFrameLayout.RESIZE_MODE_FIXED_HEIGHT);
}

enum class AspectRatio(val label: String, val ratio: Float) {
    AUTO("Auto", 0f),
    RATIO_16_9("16:9", 16f / 9f),
    RATIO_4_3("4:3", 4f / 3f),
    RATIO_21_9("21:9", 21f / 9f),
    RATIO_1_1("1:1", 1f);
}

enum class SbsMode(val label: String, val swap: Boolean = false, val vertical: Boolean = false) {
    OFF("Normal"),
    SBS_FULL("3D SBS Full — L|R"),
    SBS_FULL_SWAP("3D SBS Full — R|L (swapped)", swap = true),
    SBS_HALF("3D SBS Half — L|R"),
    SBS_HALF_SWAP("3D SBS Half — R|L (swapped)", swap = true),
    OU_FULL("3D Over/Under — T|B", vertical = true),
    OU_HALF("3D Over/Under Half — T|B", vertical = true);
}

/** Guesses a 3D mode from the filename. Returns OFF if nothing matches. */
object ThreeDDetector {
    fun detect(name: String): SbsMode {
        val n = name.lowercase()
        val isHalf = n.contains("half") || n.contains("hsbs") || n.contains("hou")
        return when {
            n.contains("ou") || n.contains("over-under") || n.contains("overunder") ||
                n.contains("tab") || n.contains("top-bottom") ->
                if (isHalf) SbsMode.OU_HALF else SbsMode.OU_FULL
            n.contains("sbs") || n.contains("3d") || n.contains("half-sbs") ->
                if (isHalf) SbsMode.SBS_HALF else SbsMode.SBS_FULL
            else -> SbsMode.OFF
        }
    }
}
