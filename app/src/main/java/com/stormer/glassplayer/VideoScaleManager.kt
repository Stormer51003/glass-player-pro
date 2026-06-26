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

enum class SbsMode(val label: String, val swap: Boolean = false) {
    OFF("Normal"),
    SBS_FULL("3D SBS Full — L|R"),
    SBS_FULL_SWAP("3D SBS Full — R|L (swapped)", swap = true),
    SBS_HALF("3D SBS Half — L|R"),
    SBS_HALF_SWAP("3D SBS Half — R|L (swapped)", swap = true);
}
