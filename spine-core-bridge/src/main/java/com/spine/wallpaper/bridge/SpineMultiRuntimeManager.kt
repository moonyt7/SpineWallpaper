package com.spine.wallpaper.bridge

import com.badlogic.gdx.graphics.g2d.PolygonSpriteBatch
import com.badlogic.gdx.graphics.g2d.TextureAtlas
import java.io.File

/**
 * Dynamic Multi-Runtime Dispatcher.
 * Matches model files to their isolated adapter without ClassLoader collisions.
 */
object SpineMultiRuntimeManager {

    interface IAdapterFactory {
        val version: SpineVersion
        fun createAdapter(skelFile: File, atlas: TextureAtlas, scale: Float, isPma: Boolean): ISpineModelAdapter
    }

    /**
     * 离屏版本嗅探器：各 runtime 模块用官方 runtime + 哑 AttachmentLoader 在无 GL 环境下
     * 解析 .skel/.json 中的动画名与皮肤名，供导入 UI 列表使用。
     * 返回 null 表示该版本解析失败（文件不匹配此版本），由上层继续尝试其他版本。
     */
    interface ISpinePeeker {
        val version: SpineVersion
        fun peek(skelFile: File): Pair<List<String>, List<String>>?
    }

    private val factories = mutableMapOf<SpineVersion, IAdapterFactory>()
    private val peekers = mutableMapOf<SpineVersion, ISpinePeeker>()

    init {
        // Auto-register built-in resilient bridge adapter factories for all Spine versions
        registerFallbackFactories()
    }

    private fun registerFallbackFactories() {
        for (v in listOf(SpineVersion.V36, SpineVersion.V37, SpineVersion.V38, SpineVersion.V40, SpineVersion.V41, SpineVersion.V42)) {
            factories[v] = object : IAdapterFactory {
                override val version: SpineVersion = v
                override fun createAdapter(skelFile: File, atlas: TextureAtlas, scale: Float, isPma: Boolean): ISpineModelAdapter {
                    return createEngineAdapter(skelFile, atlas, scale, isPma, v)
                }
            }
        }
    }

    /**
     * Registers an isolated runtime module adapter factory.
     * 各 runtime 模块在 [register] 时覆盖内置回退工厂，优先使用官方 runtime。
     */
    fun registerFactory(factory: IAdapterFactory) {
        factories[factory.version] = factory
    }

    /**
     * Registers an offscreen peeker implementation for a Spine version.
     */
    fun registerPeeker(peeker: ISpinePeeker) {
        peekers[peeker.version] = peeker
    }

    /**
     * 优先使用指定版本的官方 peeker 嗅探动画/皮肤名；失败后按版本从新到旧依次尝试。
     * 全部失败返回 null，由调用方兜底（如 JSON 解析或延迟到加载后获取）。
     */
    fun peek(version: SpineVersion, skelFile: File): Pair<List<String>, List<String>>? {
        val ordered = linkedSetOf<SpineVersion>()
        ordered.add(version)
        ordered.addAll(listOf(SpineVersion.V42, SpineVersion.V41, SpineVersion.V40, SpineVersion.V38, SpineVersion.V37, SpineVersion.V36))
        for (v in ordered) {
            val peeker = peekers[v] ?: continue
            try {
                val result = peeker.peek(skelFile)
                if (result != null && (result.first.isNotEmpty() || result.second.isNotEmpty())) {
                    return result
                }
            } catch (_: Throwable) {
            }
        }
        return null
    }

    /**
     * Automatically detects model version and instantiates the isolated adapter.
     */
    fun createModelAdapter(
        skelFile: File,
        atlas: TextureAtlas,
        scale: Float = 1.0f,
        isPma: Boolean = true,
        overrideVersion: SpineVersion = SpineVersion.UNKNOWN
    ): ISpineModelAdapter {
        val detectedVersion = if (overrideVersion != SpineVersion.UNKNOWN) {
            overrideVersion
        } else {
            SpineVersionDetector.detectVersion(skelFile)
        }

        // 按目标版本优先，未命中或加载失败时按版本从新到旧逐级降级尝试
        val order = linkedSetOf<SpineVersion>()
        order.add(detectedVersion)
        order.addAll(listOf(SpineVersion.V42, SpineVersion.V41, SpineVersion.V40, SpineVersion.V38, SpineVersion.V37, SpineVersion.V36))

        val tried = mutableSetOf<SpineVersion>()
        for (v in order) {
            if (!tried.add(v)) continue
            val factory = factories[v] ?: continue
            try {
                return factory.createAdapter(skelFile, atlas, scale, isPma)
            } catch (e: Throwable) {
                e.printStackTrace()
            }
        }

        // 最后兜底：内置容错引擎（尽量不触发）
        return createEngineAdapter(skelFile, atlas, scale, isPma, detectedVersion)
    }

    private fun createEngineAdapter(
        skelFile: File,
        atlas: TextureAtlas,
        scale: Float,
        isPma: Boolean,
        version: SpineVersion
    ): ISpineModelAdapter {
        val verStr = when (version) {
            SpineVersion.V42 -> "4.2"
            SpineVersion.V41 -> "4.1"
            SpineVersion.V40 -> "4.0"
            SpineVersion.V38 -> "3.8"
            SpineVersion.V37 -> "3.7"
            SpineVersion.V36 -> "3.6"
            else -> "3.8"
        }
        val instance = com.spine.wallpaper.bridge.engine.SpineCoreEngine.createModelInstance(
            skelFile = skelFile,
            atlas = atlas,
            scale = scale,
            isPma = isPma,
            targetVersion = verStr
        )
        return object : ISpineModelAdapter {
            override val runtimeVersion: SpineVersion = version
            override val animationNames: List<String> get() = instance.animationNames
            override val skinNames: List<String> get() = instance.skinNames
            override val bounds: FloatArray get() = instance.getBounds()
            override fun update(deltaSeconds: Float) { instance.update(deltaSeconds) }
            override fun render(batch: PolygonSpriteBatch) { instance.draw(batch) }
            override fun setAnimation(trackIndex: Int, animationName: String, loop: Boolean) {
                instance.setAnimation(trackIndex, animationName, loop)
            }
            override fun setSkin(skinName: String) { instance.setSkin(skinName) }
            override fun addSkin(skinName: String) { instance.addSkin(skinName) }
            override fun setPremultipliedAlpha(pma: Boolean) { instance.setPremultipliedAlpha(pma) }
            override fun setTransform(scale: Float, offsetX: Float, offsetY: Float) {
                instance.setScale(scale, scale)
                instance.setPosition(offsetX, offsetY)
            }
            override fun hitTest(screenX: Float, screenY: Float): String? = "body"
            override fun dispose() { instance.dispose() }
        }
    }

    fun getAvailableVersions(): List<SpineVersion> = factories.keys.toList()
}
