package com.spine.wallpaper.bridge

import com.badlogic.gdx.graphics.g2d.PolygonSpriteBatch

/**
 * Universal abstraction interface implemented by every isolated Spine runtime module (:spine-runtime-vXX).
 * Decouples the main application (:app) and wallpaper service from version-specific Spine classes.
 */
interface ISpineModelAdapter {
    /** The Spine runtime version managed by this adapter instance */
    val runtimeVersion: SpineVersion

    /** List of all animation names in the loaded skeleton */
    val animationNames: List<String>

    /** List of all skin names defined in the skeleton */
    val skinNames: List<String>

    /** Skeleton bounding box: [minX, minY, width, height] */
    val bounds: FloatArray

    /**
     * Updates animation state and skeleton transforms by delta seconds.
     */
    fun update(deltaSeconds: Float)

    /**
     * Renders the skeleton meshes into the OpenGL ES polygon batch.
     */
    fun render(batch: PolygonSpriteBatch)

    /**
     * Plays a track animation.
     */
    fun setAnimation(trackIndex: Int, animationName: String, loop: Boolean)

    /**
     * Sets the active skin by name.
     */
    fun setSkin(skinName: String)

    /**
     * Configures premultiplied alpha (PMA) blending for textures.
     */
    fun setPremultipliedAlpha(pma: Boolean)

    /**
     * Updates model scale and root position offset.
     */
    fun setTransform(scale: Float, offsetX: Float, offsetY: Float)

    /**
     * Tests whether screen coordinates (X, Y) intersect with any character bounds/hit-boxes.
     */
    fun hitTest(screenX: Float, screenY: Float): String?

    /**
     * Releases memory and native resources.
     */
    fun dispose()
}