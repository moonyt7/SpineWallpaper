package com.spine.wallpaper.adapter

import com.badlogic.gdx.graphics.g2d.PolygonSpriteBatch

/**
 * Unified Spine Model Instance interface.
 * Abstracts away differences between Spine 3.6-3.8 runtime and Spine 4.0-4.2 runtime.
 */
interface ISpineModelInstance {
    val version: String
    val animationNames: List<String>
    val skinNames: List<String>

    fun setAnimation(trackIndex: Int, name: String, loop: Boolean)
    fun setSkin(skinName: String)
    fun setPremultipliedAlpha(pma: Boolean)
    fun update(delta: Float)
    fun setPosition(x: Float, y: Float)
    fun setScale(scaleX: Float, scaleY: Float)
    fun draw(batch: PolygonSpriteBatch)
    fun dispose()
}
