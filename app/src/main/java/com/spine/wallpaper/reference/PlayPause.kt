package com.spine.wallpaper.reference

import com.esotericsoftware.spine.AnimationState

/**
 * Reference code pattern for Pause/Resume control in Spine AnimationState.
 */
class PlayPauseController(private val animationState: AnimationState) {

    private var timeScaleBackup = 1.0f
    var isPaused = false
        private set

    fun pause() {
        if (!isPaused) {
            timeScaleBackup = animationState.timeScale
            animationState.timeScale = 0.0f
            isPaused = true
        }
    }

    fun resume() {
        if (isPaused) {
            animationState.timeScale = if (timeScaleBackup > 0f) timeScaleBackup else 1.0f
            isPaused = false
        }
    }

    fun togglePlayPause() {
        if (isPaused) resume() else pause()
    }
}