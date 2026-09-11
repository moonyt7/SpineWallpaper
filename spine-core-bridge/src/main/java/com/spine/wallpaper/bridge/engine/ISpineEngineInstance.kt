package com.spine.wallpaper.bridge.engine

import com.badlogic.gdx.graphics.g2d.PolygonSpriteBatch

/**
 * Unified Spine Engine Model Instance interface.
 * Abstracts underlying runtime instances (3.8, 4.0, 4.1, 4.2) for the bridge adapters.
 */
interface ISpineEngineInstance {
    val version: String
    val animationNames: List<String>
    val skinNames: List<String>

    fun setAnimation(trackIndex: Int, name: String, loop: Boolean)
    fun setSkin(skinName: String)
    fun addSkin(skinName: String)
    fun setPremultipliedAlpha(pma: Boolean)
    fun update(delta: Float)
    fun setPosition(x: Float, y: Float)
    fun setScale(scaleX: Float, scaleY: Float)
    fun getBounds(): FloatArray = floatArrayOf(-200f, -200f, 400f, 400f)
    fun draw(batch: PolygonSpriteBatch)
    fun dispose()
}
