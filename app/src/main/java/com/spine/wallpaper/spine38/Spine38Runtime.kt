package com.spine.wallpaper.spine38

import com.badlogic.gdx.files.FileHandle
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.g2d.PolygonSpriteBatch
import com.badlogic.gdx.graphics.g2d.TextureAtlas
import com.badlogic.gdx.graphics.g2d.TextureRegion
import com.badlogic.gdx.math.MathUtils
import com.badlogic.gdx.math.Vector2
import com.badlogic.gdx.utils.FloatArray as GdxFloatArray
import com.badlogic.gdx.utils.IntArray as GdxIntArray
import com.badlogic.gdx.utils.ShortArray as GdxShortArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.EOFException
import java.io.InputStream
import java.nio.charset.StandardCharsets
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

// ==========================================
// 1. DATA DEFINITIONS (Spine 3.8 / 3.7 / 3.6)
// ==========================================

enum class TransformMode38 { normal, onlyTranslation, noRotationOrReflection, noScale, noScaleOrReflection }
enum class BlendMode38 { normal, additive, multiply, screen }
enum class MixBlend38 { setup, first, replace, add }
enum class MixDirection38 { mixIn, mixOut }

fun findRegionInAtlas(atlas: TextureAtlas, path: String?, name: String): TextureRegion? {
    if (atlas.regions.isEmpty) return null

    val candidates = LinkedHashSet<String>()
    val backslashChar = 92.toChar()
    if (!path.isNullOrEmpty()) {
        candidates.add(path)
        candidates.add(path.removeSuffix(".png").removeSuffix(".jpg").removeSuffix(".PNG").removeSuffix(".JPG"))
        val normalizedPath = path.map { if (it == backslashChar) '/' else it }.joinToString("")
        candidates.add(normalizedPath)
        candidates.add(normalizedPath.removeSuffix(".png").removeSuffix(".jpg"))
        candidates.add(normalizedPath.substringAfterLast('/'))
        candidates.add(normalizedPath.substringAfterLast('/').removeSuffix(".png").removeSuffix(".jpg"))
    }
    candidates.add(name)
    candidates.add(name.removeSuffix(".png").removeSuffix(".jpg").removeSuffix(".PNG").removeSuffix(".JPG"))
    val normalizedName = name.map { if (it == backslashChar) '/' else it }.joinToString("")
    candidates.add(normalizedName)
    candidates.add(normalizedName.removeSuffix(".png").removeSuffix(".jpg"))
    candidates.add(normalizedName.substringAfterLast('/'))
    candidates.add(normalizedName.substringAfterLast('/').removeSuffix(".png").removeSuffix(".jpg"))

    // 1. Direct libGDX lookup
    for (c in candidates) {
        if (c.isEmpty()) continue
        val r = atlas.findRegion(c)
        if (r != null) return r
    }

    // 2. Exact / case-insensitive region name matching
    for (region in atlas.regions) {
        val rName = region.name ?: continue
        for (c in candidates) {
            if (c.isNotEmpty()) {
                if (rName.equals(c, ignoreCase = true) || 
                    rName.substringAfterLast('/').equals(c, ignoreCase = true) ||
                    rName.removeSuffix(".png").equals(c.removeSuffix(".png"), ignoreCase = true)) {
                    return region
                }
            }
        }
    }

    // 3. Substring / fuzzy matching
    for (region in atlas.regions) {
        val rName = region.name?.lowercase() ?: continue
        for (c in candidates) {
            val lc = c.lowercase()
            if (lc.length >= 3 && (rName.contains(lc) || lc.contains(rName))) {
                return region
            }
        }
    }

    // 4. Fallback to first region if available
    return atlas.regions.first()
}

class BoneData38(
    val index: Int,
    val name: String,
    val parent: BoneData38? = null
) {
    var length: Float = 0f
    var x: Float = 0f
    var y: Float = 0f
    var rotation: Float = 0f
    var scaleX: Float = 1f
    var scaleY: Float = 1f
    var shearX: Float = 0f
    var shearY: Float = 0f
    var transformMode: TransformMode38 = TransformMode38.normal
    var skinRequired: Boolean = false
}

class SlotData38(
    val index: Int,
    val name: String,
    val boneData: BoneData38
) {
    val color: Color = Color(1f, 1f, 1f, 1f)
    var darkColor: Color? = null
    var attachmentName: String? = null
    var blendMode: BlendMode38 = BlendMode38.normal
}

data class SkinKey38(val slotIndex: Int, val name: String)

class Skin38(val name: String) {
    val attachments = mutableMapOf<SkinKey38, Attachment38>()

    fun setAttachment(slotIndex: Int, name: String, attachment: Attachment38) {
        attachments[SkinKey38(slotIndex, name)] = attachment
    }

    fun getAttachment(slotIndex: Int, name: String): Attachment38? {
        return attachments[SkinKey38(slotIndex, name)]
    }
}

class IkConstraintData38(val name: String) {
    var order: Int = 0
    var skinRequired: Boolean = false
    val bones = mutableListOf<BoneData38>()
    var target: BoneData38? = null
    var mix: Float = 1f
    var softness: Float = 0f
    var bendDirection: Int = 1
    var compress: Boolean = false
    var stretch: Boolean = false
    var uniform: Boolean = false
}

class TransformConstraintData38(val name: String) {
    var order: Int = 0
    var skinRequired: Boolean = false
    val bones = mutableListOf<BoneData38>()
    var target: BoneData38? = null
    var rotateMix: Float = 1f
    var translateMix: Float = 1f
    var scaleMix: Float = 1f
    var shearMix: Float = 1f
    var offsetRotation: Float = 0f
    var offsetX: Float = 0f
    var offsetY: Float = 0f
    var offsetScaleX: Float = 0f
    var offsetScaleY: Float = 0f
    var offsetShearY: Float = 0f
    var relative: Boolean = false
    var local: Boolean = false
}

class PathConstraintData38(val name: String) {
    var order: Int = 0
    var skinRequired: Boolean = false
    val bones = mutableListOf<BoneData38>()
    var target: SlotData38? = null
    var positionMode: Int = 0
    var spacingMode: Int = 0
    var rotateMode: Int = 0
    var offsetRotation: Float = 0f
    var position: Float = 0f
    var spacing: Float = 0f
    var rotateMix: Float = 1f
    var translateMix: Float = 1f
}

class EventData38(val name: String) {
    var intValue: Int = 0
    var floatValue: Float = 0f
    var stringValue: String? = null
    var audioPath: String? = null
    var volume: Float = 1f
    var balance: Float = 0f
}

class SkeletonData38 {
    var name: String? = null
    var atlas: TextureAtlas? = null
    val bones = mutableListOf<BoneData38>()
    val slots = mutableListOf<SlotData38>()
    val skins = mutableListOf<Skin38>()
    var defaultSkin: Skin38? = null
    val events = mutableListOf<EventData38>()
    val animations = mutableListOf<Animation38>()
    val ikConstraints = mutableListOf<IkConstraintData38>()
    val transformConstraints = mutableListOf<TransformConstraintData38>()
    val pathConstraints = mutableListOf<PathConstraintData38>()
    var width: Float = 0f
    var height: Float = 0f
    var x: Float = 0f
    var y: Float = 0f
    var fps: Float = 30f
    var imagesPath: String? = null
    var audioPath: String? = null
    var version: String? = "3.8"
    var hash: String? = null

    fun findBone(name: String): BoneData38? = bones.firstOrNull { it.name == name }
    fun findSlot(name: String): SlotData38? = slots.firstOrNull { it.name == name }
    fun findSkin(name: String): Skin38? = skins.firstOrNull { it.name == name }
    fun findAnimation(name: String): Animation38? = animations.firstOrNull { it.name == name }
}

// ==========================================
// 2. ATTACHMENTS (Region, Mesh, etc.)
// ==========================================

abstract class Attachment38(val name: String)

class RegionAttachment38(name: String) : Attachment38(name) {
    var x: Float = 0f
    var y: Float = 0f
    var scaleX: Float = 1f
    var scaleY: Float = 1f
    var rotation: Float = 0f
    var width: Float = 0f
    var height: Float = 0f
    val color: Color = Color(1f, 1f, 1f, 1f)
    var path: String? = null
    var region: TextureRegion? = null
    val offset = FloatArray(8)
    val uvs = FloatArray(8)

    fun updateOffset() {
        val width = this.width
        val height = this.height
        var localX = -width / 2f
        var localY = -height / 2f
        var localX2 = width / 2f
        var localY2 = height / 2f
        val reg = this.region
        if (reg is TextureAtlas.AtlasRegion) {
            if (reg.rotate) {
                localX += reg.offsetX / reg.originalWidth * width
                localY += reg.offsetY / reg.originalHeight * height
                localX2 -= (reg.originalWidth - reg.offsetX - reg.packedHeight) / reg.originalWidth * width
                localY2 -= (reg.originalHeight - reg.offsetY - reg.packedWidth) / reg.originalHeight * height
            } else {
                localX += reg.offsetX / reg.originalWidth * width
                localY += reg.offsetY / reg.originalHeight * height
                localX2 -= (reg.originalWidth - reg.offsetX - reg.packedWidth) / reg.originalWidth * width
                localY2 -= (reg.originalHeight - reg.offsetY - reg.packedHeight) / reg.originalHeight * height
            }
        }
        val scaleX = this.scaleX
        val scaleY = this.scaleY
        localX *= scaleX
        localY *= scaleY
        localX2 *= scaleX
        localY2 *= scaleY
        val radians = this.rotation * MathUtils.degRad
        val cos = cos(radians)
        val sin = sin(radians)
        val x = this.x
        val y = this.y
        val localXCos = localX * cos + x
        val localXSin = localX * sin
        val localYCos = localY * cos + y
        val localYSin = localY * sin
        val localX2Cos = localX2 * cos + x
        val localX2Sin = localX2 * sin
        val localY2Cos = localY2 * cos + y
        val localY2Sin = localY2 * sin
        // BL
        offset[0] = localXCos - localYSin
        offset[1] = localYCos + localXSin
        // UL
        offset[2] = localXCos - localY2Sin
        offset[3] = localY2Cos + localXSin
        // UR
        offset[4] = localX2Cos - localY2Sin
        offset[5] = localY2Cos + localX2Sin
        // BR
        offset[6] = localX2Cos - localYSin
        offset[7] = localYCos + localX2Sin
    }

    fun updateUVs() {
        val reg = region ?: return
        val u = reg.u
        val v = reg.v
        val u2 = reg.u2
        val v2 = reg.v2
        if (reg is TextureAtlas.AtlasRegion && reg.rotate) {
            uvs[4] = u;  uvs[5] = v2 // UR
            uvs[6] = u;  uvs[7] = v  // BR
            uvs[0] = u2; uvs[1] = v  // BL
            uvs[2] = u2; uvs[3] = v2 // UL
        } else {
            uvs[0] = u;  uvs[1] = v2 // BL
            uvs[2] = u;  uvs[3] = v  // UL
            uvs[4] = u2; uvs[5] = v  // UR
            uvs[6] = u2; uvs[7] = v2 // BR
        }
    }

    fun computeWorldVertices(bone: Bone38, worldVertices: FloatArray, offset: Int, stride: Int) {
        val off = this.offset
        val bA = bone.a
        val bB = bone.b
        val bC = bone.c
        val bD = bone.d
        val bX = bone.worldX
        val bY = bone.worldY

        var wOff = offset
        for (i in 0 until 8 step 2) {
            val ox = off[i]
            val oy = off[i + 1]
            worldVertices[wOff] = ox * bA + oy * bB + bX
            worldVertices[wOff + 1] = ox * bC + oy * bD + bY
            wOff += stride
        }
    }
}

class MeshAttachment38(name: String) : Attachment38(name) {
    var region: TextureRegion? = null
    var path: String? = null
    var regionUVs = FloatArray(0)
    var uvs = FloatArray(0)
    var triangles = ShortArray(0)
    var bones: IntArray? = null
    var vertices = FloatArray(0)
    var worldVerticesLength: Int = 0
    val color: Color = Color(1f, 1f, 1f, 1f)
    var width: Float = 0f
    var height: Float = 0f
    var hullLength: Int = 0
    var parentMesh: MeshAttachment38? = null

    fun updateUVs() {
        val reg = region ?: return
        val u = reg.u
        val v = reg.v
        val width = reg.u2 - u
        val height = reg.v2 - v
        val n = regionUVs.size
        if (uvs.size != n) uvs = FloatArray(n)
        if (reg is TextureAtlas.AtlasRegion && reg.rotate) {
            for (i in 0 until n step 2) {
                uvs[i] = u + regionUVs[i + 1] * width
                uvs[i + 1] = v + (1f - regionUVs[i]) * height
            }
        } else {
            for (i in 0 until n step 2) {
                uvs[i] = u + regionUVs[i] * width
                uvs[i + 1] = v + regionUVs[i + 1] * height
            }
        }
    }

    fun computeWorldVertices(slot: Slot38, start: Int, count: Int, worldVertices: FloatArray, offset: Int, stride: Int) {
        val skeleton = slot.bone.skeleton
        val deform = slot.deform
        val vertices = this.vertices
        val bones = this.bones

        if (bones == null) {
            var v = start
            var w = offset
            val bA = slot.bone.a
            val bB = slot.bone.b
            val bC = slot.bone.c
            val bD = slot.bone.d
            val bX = slot.bone.worldX
            val bY = slot.bone.worldY

            val targetEnd = offset + (count / 2) * stride
            while (w < targetEnd && v + 1 < vertices.size && w + 1 < worldVertices.size) {
                val dfx = if (v < deform.size) deform[v] else 0f
                val dfy = if (v + 1 < deform.size) deform[v + 1] else 0f
                val vx = vertices[v] + dfx
                val vy = vertices[v + 1] + dfy
                worldVertices[w] = vx * bA + vy * bB + bX
                worldVertices[w + 1] = vx * bC + vy * bD + bY
                v += 2
                w += stride
            }
            return
        }

        var v = 0
        var skip = 0
        for (i in 0 until start step 2) {
            if (v >= bones.size) break
            val n = bones[v]
            v += n + 1
            skip += n
        }

        val skeletonBones = skeleton.bones
        val targetEnd = offset + (count / 2) * stride
        var w = offset
        var b = skip * 3
        var f = skip * 2
        while (w < targetEnd && v < bones.size && w + 1 < worldVertices.size) {
            var wx = 0f
            var wy = 0f
            val boneCount = bones[v++]
            val dfx = if (f < deform.size) deform[f] else 0f
            val dfy = if (f + 1 < deform.size) deform[f + 1] else 0f
            f += 2
            for (i in 0 until boneCount) {
                if (v >= bones.size || b + 2 >= vertices.size) break
                val boneIdx = bones[v++]
                val bone = if (boneIdx in skeletonBones.indices) skeletonBones[boneIdx] else slot.bone
                val vx = vertices[b++] + dfx
                val vy = vertices[b++] + dfy
                val weight = vertices[b++]
                wx += (vx * bone.a + vy * bone.b + bone.worldX) * weight
                wy += (vx * bone.c + vy * bone.d + bone.worldY) * weight
            }
            worldVertices[w] = wx
            worldVertices[w + 1] = wy
            w += stride
        }
    }
}

// ==========================================
// 3. RUNTIME SKELETON & BONES
// ==========================================

class Bone38(val data: BoneData38, val skeleton: Skeleton38, val parent: Bone38?) {
    var x: Float = data.x
    var y: Float = data.y
    var rotation: Float = data.rotation
    var scaleX: Float = data.scaleX
    var scaleY: Float = data.scaleY
    var shearX: Float = data.shearX
    var shearY: Float = data.shearY
    var ax: Float = data.x
    var ay: Float = data.y
    var arotation: Float = data.rotation
    var ascaleX: Float = data.scaleX
    var ascaleY: Float = data.scaleY
    var ashearX: Float = data.shearX
    var ashearY: Float = data.shearY

    var a: Float = 1f
    var b: Float = 0f
    var c: Float = 0f
    var d: Float = 1f
    var worldX: Float = 0f
    var worldY: Float = 0f

    fun setToSetupPose() {
        x = data.x
        y = data.y
        rotation = data.rotation
        scaleX = data.scaleX
        scaleY = data.scaleY
        shearX = data.shearX
        shearY = data.shearY
        ax = x; ay = y; arotation = rotation; ascaleX = scaleX; ascaleY = scaleY; ashearX = shearX; ashearY = shearY
    }

    fun updateWorldTransform() {
        updateWorldTransform(x, y, rotation, scaleX, scaleY, shearX, shearY)
    }

    fun updateWorldTransform(x: Float, y: Float, rotation: Float, scaleX: Float, scaleY: Float, shearX: Float, shearY: Float) {
        ax = x; ay = y; arotation = rotation; ascaleX = scaleX; ascaleY = scaleY; ashearX = shearX; ashearY = shearY
        val parent = this.parent
        if (parent == null) {
            val rotationY = rotation + 90f + shearY
            val sx = skeleton.scaleX
            val sy = skeleton.scaleY
            val radX = rotation * MathUtils.degRad
            val radY = rotationY * MathUtils.degRad
            val cosX = cos(radX) * scaleX * sx
            val sinX = sin(radX) * scaleX * sx
            val cosY = cos(radY) * scaleY * sy
            val sinY = sin(radY) * scaleY * sy
            a = cosX
            b = cosY
            c = sinX
            d = sinY
            worldX = x * sx + skeleton.x
            worldY = y * sy + skeleton.y
            return
        }

        val pa = parent.a
        val pb = parent.b
        val pc = parent.c
        val pd = parent.d
        worldX = pa * x + pb * y + parent.worldX
        worldY = pc * x + pd * y + parent.worldY

        val rotationY = rotation + 90f + shearY
        val radX = rotation * MathUtils.degRad
        val radY = rotationY * MathUtils.degRad
        val cosX = cos(radX) * scaleX
        val sinX = sin(radX) * scaleX
        val cosY = cos(radY) * scaleY
        val sinY = sin(radY) * scaleY

        a = pa * cosX + pb * sinX
        b = pa * cosY + pb * sinY
        c = pc * cosX + pd * sinX
        d = pc * cosY + pd * sinY
    }
}

class Slot38(val data: SlotData38, val bone: Bone38) {
    val color: Color = Color(data.color)
    var darkColor: Color? = data.darkColor?.let { Color(it) }
    var attachment: Attachment38? = null
    var attachmentTime: Float = 0f
    var deform = FloatArray(0)

    fun setToSetupPose() {
        color.set(data.color)
        darkColor = data.darkColor?.let { Color(it) }
        val attName = data.attachmentName
        if (attName != null) {
            attachment = bone.skeleton.getAttachment(data.index, attName)
        }
        if (attachment == null) {
            val sk = bone.skeleton.skin ?: bone.skeleton.data.defaultSkin
            attachment = sk?.attachments?.entries?.firstOrNull { it.key.slotIndex == data.index }?.value
        }
    }
}

class Skeleton38(val data: SkeletonData38) {
    val bones = mutableListOf<Bone38>()
    val slots = mutableListOf<Slot38>()
    val drawOrder = mutableListOf<Slot38>()
    var skin: Skin38? = null
    val color: Color = Color(1f, 1f, 1f, 1f)
    var time: Float = 0f
    var scaleX: Float = 1f
    var scaleY: Float = 1f
    var x: Float = 0f
    var y: Float = 0f

    init {
        for (boneData in data.bones) {
            val parentBone = boneData.parent?.let { bones[it.index] }
            bones.add(Bone38(boneData, this, parentBone))
        }
        for (slotData in data.slots) {
            val bone = bones[slotData.boneData.index]
            val slot = Slot38(slotData, bone)
            slots.add(slot)
            drawOrder.add(slot)
        }
        val initialSkin = data.defaultSkin ?: data.skins.firstOrNull()
        if (initialSkin != null) {
            this.skin = initialSkin
        }
        setToSetupPose()
    }

    fun setToSetupPose() {
        setBonesToSetupPose()
        setSlotsToSetupPose()
    }

    fun setBonesToSetupPose() {
        for (bone in bones) bone.setToSetupPose()
    }

    fun setSlotsToSetupPose() {
        drawOrder.clear()
        drawOrder.addAll(slots)
        for (slot in slots) slot.setToSetupPose()
    }

    fun setSkin(skinName: String) {
        val foundSkin = data.findSkin(skinName) ?: data.defaultSkin ?: data.skins.firstOrNull()
        this.skin = foundSkin
        if (foundSkin != null) {
            for (slot in slots) {
                val attName = slot.data.attachmentName
                if (attName != null) {
                    val att = foundSkin.getAttachment(slot.data.index, attName)
                        ?: data.defaultSkin?.getAttachment(slot.data.index, attName)
                        ?: data.skins.firstNotNullOfOrNull { it.getAttachment(slot.data.index, attName) }
                    if (att != null) slot.attachment = att
                }
                if (slot.attachment == null) {
                    val fallback = foundSkin.attachments.entries.firstOrNull { it.key.slotIndex == slot.data.index }?.value
                        ?: data.defaultSkin?.attachments?.entries?.firstOrNull { it.key.slotIndex == slot.data.index }?.value
                    if (fallback != null) slot.attachment = fallback
                }
            }
        }
    }

    fun getAttachment(slotIndex: Int, attachmentName: String): Attachment38? {
        val found = skin?.getAttachment(slotIndex, attachmentName)
        if (found != null) return found
        val defFound = data.defaultSkin?.getAttachment(slotIndex, attachmentName)
        if (defFound != null) return defFound
        for (s in data.skins) {
            val a = s.getAttachment(slotIndex, attachmentName)
            if (a != null) return a
        }
        return null
    }

    fun updateWorldTransform() {
        for (bone in bones) {
            bone.updateWorldTransform()
        }
    }

    fun setPosition(x: Float, y: Float) {
        this.x = x
        this.y = y
    }
}

// ==========================================
// 4. ANIMATION & TIMELINES (Spine 3.8)
// ==========================================

class Animation38(val name: String, val duration: Float) {
    val timelines = mutableListOf<Timeline38>()

    fun apply(skeleton: Skeleton38, lastTime: Float, time: Float, loop: Boolean, alpha: Float, blend: MixBlend38, direction: MixDirection38) {
        var animTime = time
        if (loop && duration > 0f) {
            animTime %= duration
        }
        for (timeline in timelines) {
            timeline.apply(skeleton, lastTime, animTime, alpha, blend, direction)
        }
    }
}

interface Timeline38 {
    fun apply(skeleton: Skeleton38, lastTime: Float, time: Float, alpha: Float, blend: MixBlend38, direction: MixDirection38)
}

abstract class CurveTimeline38(val frameCount: Int) : Timeline38 {
    val curves = FloatArray((frameCount - 1) * 19)

    fun setLinear(frameIndex: Int) {
        curves[frameIndex * 19] = 0f
    }

    fun setStepped(frameIndex: Int) {
        curves[frameIndex * 19] = 1f
    }

    fun getCurvePercent(frameIndex: Int, percent: Float): Float {
        var p = percent.coerceIn(0f, 1f)
        val i = frameIndex * 19
        val type = curves[i]
        if (type == 0f) return p
        if (type == 1f) return 0f
        return p
    }
}

class RotateTimeline38(frameCount: Int) : CurveTimeline38(frameCount) {
    var boneIndex: Int = 0
    val frames = FloatArray(frameCount * 2) // time, degrees

    fun setFrame(frameIndex: Int, time: Float, degrees: Float) {
        frames[frameIndex * 2] = time
        frames[frameIndex * 2 + 1] = degrees
    }

    override fun apply(skeleton: Skeleton38, lastTime: Float, time: Float, alpha: Float, blend: MixBlend38, direction: MixDirection38) {
        val frames = this.frames
        val bone = skeleton.bones[boneIndex]
        if (time < frames[0]) {
            if (blend == MixBlend38.setup || blend == MixBlend38.first) bone.rotation = bone.data.rotation
            return
        }
        if (time >= frames[frames.size - 2]) {
            val amount = frames[frames.size - 1]
            if (blend == MixBlend38.setup || blend == MixBlend38.first) {
                bone.rotation = bone.data.rotation + amount * alpha
            } else {
                bone.rotation += amount * alpha
            }
            return
        }

        val frame = binarySearch(frames, time, 2)
        val prevRotation = frames[frame - 1]
        val frameTime = frames[frame]
        val percent = getCurvePercent(frame / 2 - 1, 1f - (time - frameTime) / (frames[frame - 2] - frameTime))

        var amount = frames[frame + 1] - prevRotation
        while (amount > 180f) amount -= 360f
        while (amount < -180f) amount += 360f
        amount = prevRotation + amount * percent

        if (blend == MixBlend38.setup || blend == MixBlend38.first) {
            bone.rotation = bone.data.rotation + amount * alpha
        } else {
            bone.rotation += amount * alpha
        }
    }
}

class TranslateTimeline38(frameCount: Int) : CurveTimeline38(frameCount) {
    var boneIndex: Int = 0
    val frames = FloatArray(frameCount * 3) // time, x, y

    fun setFrame(frameIndex: Int, time: Float, x: Float, y: Float) {
        frames[frameIndex * 3] = time
        frames[frameIndex * 3 + 1] = x
        frames[frameIndex * 3 + 2] = y
    }

    override fun apply(skeleton: Skeleton38, lastTime: Float, time: Float, alpha: Float, blend: MixBlend38, direction: MixDirection38) {
        val frames = this.frames
        val bone = skeleton.bones[boneIndex]
        if (time < frames[0]) {
            if (blend == MixBlend38.setup || blend == MixBlend38.first) {
                bone.x = bone.data.x
                bone.y = bone.data.y
            }
            return
        }
        if (time >= frames[frames.size - 3]) {
            val x = frames[frames.size - 2]
            val y = frames[frames.size - 1]
            if (blend == MixBlend38.setup || blend == MixBlend38.first) {
                bone.x = bone.data.x + x * alpha
                bone.y = bone.data.y + y * alpha
            } else {
                bone.x += x * alpha
                bone.y += y * alpha
            }
            return
        }

        val frame = binarySearch(frames, time, 3)
        val prevX = frames[frame - 2]
        val prevY = frames[frame - 1]
        val frameTime = frames[frame]
        val percent = getCurvePercent(frame / 3 - 1, 1f - (time - frameTime) / (frames[frame - 3] - frameTime))

        val x = prevX + (frames[frame + 1] - prevX) * percent
        val y = prevY + (frames[frame + 2] - prevY) * percent

        if (blend == MixBlend38.setup || blend == MixBlend38.first) {
            bone.x = bone.data.x + x * alpha
            bone.y = bone.data.y + y * alpha
        } else {
            bone.x += x * alpha
            bone.y += y * alpha
        }
    }
}

class ScaleTimeline38(frameCount: Int) : CurveTimeline38(frameCount) {
    var boneIndex: Int = 0
    val frames = FloatArray(frameCount * 3) // time, x, y

    fun setFrame(frameIndex: Int, time: Float, x: Float, y: Float) {
        frames[frameIndex * 3] = time
        frames[frameIndex * 3 + 1] = x
        frames[frameIndex * 3 + 2] = y
    }

    override fun apply(skeleton: Skeleton38, lastTime: Float, time: Float, alpha: Float, blend: MixBlend38, direction: MixDirection38) {
        val frames = this.frames
        val bone = skeleton.bones[boneIndex]
        if (time < frames[0]) {
            if (blend == MixBlend38.setup || blend == MixBlend38.first) {
                bone.scaleX = bone.data.scaleX
                bone.scaleY = bone.data.scaleY
            }
            return
        }
        if (time >= frames[frames.size - 3]) {
            val x = frames[frames.size - 2]
            val y = frames[frames.size - 1]
            if (blend == MixBlend38.setup || blend == MixBlend38.first) {
                bone.scaleX = bone.data.scaleX * (1f + (x - 1f) * alpha)
                bone.scaleY = bone.data.scaleY * (1f + (y - 1f) * alpha)
            } else {
                bone.scaleX += (x - 1f) * bone.data.scaleX * alpha
                bone.scaleY += (y - 1f) * bone.data.scaleY * alpha
            }
            return
        }

        val frame = binarySearch(frames, time, 3)
        val prevX = frames[frame - 2]
        val prevY = frames[frame - 1]
        val frameTime = frames[frame]
        val percent = getCurvePercent(frame / 3 - 1, 1f - (time - frameTime) / (frames[frame - 3] - frameTime))

        val x = prevX + (frames[frame + 1] - prevX) * percent
        val y = prevY + (frames[frame + 2] - prevY) * percent

        if (blend == MixBlend38.setup || blend == MixBlend38.first) {
            bone.scaleX = bone.data.scaleX * (1f + (x - 1f) * alpha)
            bone.scaleY = bone.data.scaleY * (1f + (y - 1f) * alpha)
        } else {
            bone.scaleX += (x - 1f) * bone.data.scaleX * alpha
            bone.scaleY += (y - 1f) * bone.data.scaleY * alpha
        }
    }
}

class AttachmentTimeline38(frameCount: Int) : Timeline38 {
    var slotIndex: Int = 0
    val frames = FloatArray(frameCount)
    val attachmentNames = arrayOfNulls<String>(frameCount)

    fun setFrame(frameIndex: Int, time: Float, attachmentName: String?) {
        frames[frameIndex] = time
        attachmentNames[frameIndex] = attachmentName
    }

    override fun apply(skeleton: Skeleton38, lastTime: Float, time: Float, alpha: Float, blend: MixBlend38, direction: MixDirection38) {
        val slot = skeleton.slots[slotIndex]
        if (direction == MixDirection38.mixOut && blend == MixBlend38.setup) {
            val attName = slot.data.attachmentName
            slot.attachment = if (attName == null) null else skeleton.getAttachment(slotIndex, attName)
            return
        }

        val frames = this.frames
        if (time < frames[0]) {
            if (blend == MixBlend38.setup) {
                val attName = slot.data.attachmentName
                slot.attachment = if (attName == null) null else skeleton.getAttachment(slotIndex, attName)
            }
            return
        }

        var frameIndex = if (time >= frames[frames.size - 1]) frames.size - 1 else binarySearch(frames, time, 1) - 1
        val attName = attachmentNames[frameIndex]
        slot.attachment = if (attName == null) null else skeleton.getAttachment(slotIndex, attName)
    }
}

class DeformTimeline38(frameCount: Int) : CurveTimeline38(frameCount) {
    var slotIndex: Int = 0
    var skin: Skin38? = null
    var attachment: Attachment38? = null
    val frames = FloatArray(frameCount)
    val frameVertices = arrayOfNulls<FloatArray>(frameCount)

    fun setFrame(frameIndex: Int, time: Float, vertices: FloatArray) {
        frames[frameIndex] = time
        frameVertices[frameIndex] = vertices
    }

    override fun apply(skeleton: Skeleton38, lastTime: Float, time: Float, alpha: Float, blend: MixBlend38, direction: MixDirection38) {
        val slot = skeleton.slots[slotIndex]
        val slotAtt = slot.attachment
        if (slotAtt !is MeshAttachment38) return

        val frames = this.frames
        if (time < frames[0]) return

        val frameVertices = this.frameVertices
        val vertexCount = frameVertices[0]!!.size

        if (slot.deform.size != vertexCount) {
            slot.deform = FloatArray(vertexCount)
        }
        val deform = slot.deform

        if (time >= frames[frames.size - 1]) {
            val lastV = frameVertices[frames.size - 1]!!
            System.arraycopy(lastV, 0, deform, 0, vertexCount)
            return
        }

        val frame = binarySearch(frames, time, 1)
        val prevV = frameVertices[frame - 1]!!
        val nextV = frameVertices[frame]!!
        val frameTime = frames[frame]
        val percent = getCurvePercent(frame - 1, 1f - (time - frameTime) / (frames[frame - 1] - frameTime))

        for (i in 0 until vertexCount) {
            val p = prevV[i]
            deform[i] = p + (nextV[i] - p) * percent
        }
    }
}

private fun binarySearch(values: FloatArray, target: Float, step: Int): Int {
    var low = 0
    var high = values.size / step - 2
    if (high <= 0) return step
    var current = high ushr 1
    while (true) {
        if (values[(current + 1) * step] <= target) {
            low = current + 1
        } else {
            high = current
        }
        if (low == high) return (low + 1) * step
        current = (low + high) ushr 1
    }
}

// ==========================================
// 5. ANIMATION STATE & ENGINE LOOP
// ==========================================

class TrackEntry38 {
    var animation: Animation38? = null
    var loop: Boolean = false
    var trackTime: Float = 0f
    var timeScale: Float = 1f
    var alpha: Float = 1f
    var mixTime: Float = 0f
    var mixDuration: Float = 0f
}

class AnimationState38(val data: SkeletonData38) {
    val tracks = mutableListOf<TrackEntry38?>()

    fun setAnimation(trackIndex: Int, animName: String, loop: Boolean): TrackEntry38? {
        val anim = data.findAnimation(animName) ?: return null
        while (tracks.size <= trackIndex) tracks.add(null)
        val entry = TrackEntry38().apply {
            this.animation = anim
            this.loop = loop
            this.trackTime = 0f
        }
        tracks[trackIndex] = entry
        return entry
    }

    fun update(delta: Float) {
        for (entry in tracks) {
            if (entry?.animation != null) {
                entry.trackTime += delta * entry.timeScale
            }
        }
    }

    fun apply(skeleton: Skeleton38) {
        skeleton.setBonesToSetupPose()
        for (entry in tracks) {
            if (entry != null && entry.animation != null) {
                entry.animation!!.apply(
                    skeleton,
                    0f,
                    entry.trackTime,
                    entry.loop,
                    entry.alpha,
                    MixBlend38.first,
                    MixDirection38.mixIn
                )
            }
        }
    }
}

// ==========================================
// 6. SKELETON RENDERER (PolygonSpriteBatch)
// ==========================================

class SkeletonRenderer38 {
    var pma: Boolean = true
    private val quadTriangles = shortArrayOf(0, 1, 2, 2, 3, 0)

    fun setPremultipliedAlpha(pma: Boolean) {
        this.pma = pma
    }

    private fun colorToFloatBits(r: Float, g: Float, b: Float, a: Float): Float {
        val ir = (r.coerceIn(0f, 1f) * 255f).toInt()
        val ig = (g.coerceIn(0f, 1f) * 255f).toInt()
        val ib = (b.coerceIn(0f, 1f) * 255f).toInt()
        val ia = (a.coerceIn(0f, 1f) * 255f).toInt()
        return Color.toFloatBits(ir, ig, ib, ia)
    }

    fun draw(batch: PolygonSpriteBatch, skeleton: Skeleton38) {
        val drawOrder = skeleton.drawOrder
        val skelColor = skeleton.color

        for (slot in drawOrder) {
            val attachment = slot.attachment ?: continue
            val bone = slot.bone
            val slotColor = slot.color

            val r = (skelColor.r * slotColor.r).coerceIn(0f, 1f)
            val g = (skelColor.g * slotColor.g).coerceIn(0f, 1f)
            val b = (skelColor.b * slotColor.b).coerceIn(0f, 1f)
            val a = (skelColor.a * slotColor.a).coerceIn(0f, 1f)

            // Set blend mode
            when (slot.data.blendMode) {
                BlendMode38.normal -> {
                    if (pma) {
                        batch.setBlendFunction(GL20.GL_ONE, GL20.GL_ONE_MINUS_SRC_ALPHA)
                    } else {
                        batch.setBlendFunction(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA)
                    }
                }
                BlendMode38.additive -> {
                    if (pma) {
                        batch.setBlendFunction(GL20.GL_ONE, GL20.GL_ONE)
                    } else {
                        batch.setBlendFunction(GL20.GL_SRC_ALPHA, GL20.GL_ONE)
                    }
                }
                BlendMode38.multiply -> {
                    batch.setBlendFunction(GL20.GL_DST_COLOR, GL20.GL_ONE_MINUS_SRC_ALPHA)
                }
                BlendMode38.screen -> {
                    batch.setBlendFunction(GL20.GL_ONE, GL20.GL_ONE_MINUS_SRC_COLOR)
                }
            }

            if (attachment is RegionAttachment38) {
                var region = attachment.region
                if (region == null) {
                    val atlas = skeleton.data.atlas
                    if (atlas != null) {
                        region = findRegionInAtlas(atlas, attachment.path, attachment.name)
                        attachment.region = region
                        if (region != null) {
                            attachment.updateOffset()
                            attachment.updateUVs()
                        }
                    }
                }
                if (region == null) continue

                val cFloat = if (pma) {
                    val ar = (r * a * attachment.color.r).coerceIn(0f, 1f)
                    val ag = (g * a * attachment.color.g).coerceIn(0f, 1f)
                    val ab = (b * a * attachment.color.b).coerceIn(0f, 1f)
                    val aa = (a * attachment.color.a).coerceIn(0f, 1f)
                    colorToFloatBits(ar, ag, ab, aa)
                } else {
                    colorToFloatBits(r * attachment.color.r, g * attachment.color.g, b * attachment.color.b, a * attachment.color.a)
                }

                val drawVertices = FloatArray(20)
                attachment.computeWorldVertices(bone, drawVertices, 0, 5)

                val uvs = attachment.uvs
                // Format: [x, y, color, u, v] * 4
                if (uvs.size >= 8) {
                    drawVertices[2] = cFloat; drawVertices[3] = uvs[0]; drawVertices[4] = uvs[1]
                    drawVertices[7] = cFloat; drawVertices[8] = uvs[2]; drawVertices[9] = uvs[3]
                    drawVertices[12] = cFloat; drawVertices[13] = uvs[4]; drawVertices[14] = uvs[5]
                    drawVertices[17] = cFloat; drawVertices[18] = uvs[6]; drawVertices[19] = uvs[7]
                } else {
                    drawVertices[2] = cFloat; drawVertices[3] = region.u; drawVertices[4] = region.v2
                    drawVertices[7] = cFloat; drawVertices[8] = region.u; drawVertices[9] = region.v
                    drawVertices[12] = cFloat; drawVertices[13] = region.u2; drawVertices[14] = region.v
                    drawVertices[17] = cFloat; drawVertices[18] = region.u2; drawVertices[19] = region.v2
                }

                batch.draw(region.texture, drawVertices, 0, 20, quadTriangles, 0, 6)

            } else if (attachment is MeshAttachment38) {
                var region = attachment.region
                if (region == null) {
                    val atlas = skeleton.data.atlas
                    if (atlas != null) {
                        region = findRegionInAtlas(atlas, attachment.path, attachment.name)
                        attachment.region = region
                        if (region != null) {
                            attachment.updateUVs()
                        }
                    }
                }
                if (region == null) continue
                val count = attachment.worldVerticesLength
                val numVertices = if (count > 0) count / 2 else (attachment.regionUVs.size / 2)
                if (numVertices <= 0) continue
                val requiredFloats = numVertices * 5
                val wVerts = FloatArray(requiredFloats)

                attachment.computeWorldVertices(slot, 0, numVertices * 2, wVerts, 0, 5)

                val cFloat = if (pma) {
                    val ar = (r * a * attachment.color.r).coerceIn(0f, 1f)
                    val ag = (g * a * attachment.color.g).coerceIn(0f, 1f)
                    val ab = (b * a * attachment.color.b).coerceIn(0f, 1f)
                    val aa = (a * attachment.color.a).coerceIn(0f, 1f)
                    colorToFloatBits(ar, ag, ab, aa)
                } else {
                    colorToFloatBits(r * attachment.color.r, g * attachment.color.g, b * attachment.color.b, a * attachment.color.a)
                }

                val uvs = if (attachment.uvs.isNotEmpty()) attachment.uvs else attachment.regionUVs
                var uvIndex = 0
                for (v in 0 until numVertices) {
                    val idx = v * 5
                    wVerts[idx + 2] = cFloat
                    if (uvIndex + 1 < uvs.size) {
                        wVerts[idx + 3] = uvs[uvIndex++]
                        wVerts[idx + 4] = uvs[uvIndex++]
                    } else {
                        wVerts[idx + 3] = 0f
                        wVerts[idx + 4] = 0f
                    }
                }

                val triangles = if (attachment.triangles.isNotEmpty()) {
                    attachment.triangles
                } else {
                    val tris = ShortArray((numVertices - 2) * 3)
                    var t = 0
                    for (i in 1 until numVertices - 1) {
                        tris[t++] = 0
                        tris[t++] = i.toShort()
                        tris[t++] = (i + 1).toShort()
                    }
                    tris
                }

                if (triangles.isNotEmpty()) {
                    batch.draw(region.texture, wVerts, 0, requiredFloats, triangles, 0, triangles.size)
                }
            }
        }
    }
}

// ==========================================
// 7. SKELETON BINARY PARSER (Spine 3.8 / 3.7 / 3.6 .skel)
// ==========================================

data class LinkedMesh38(
    val mesh: MeshAttachment38,
    val skinName: String?,
    val slotIndex: Int,
    val parentName: String?,
    val inheritDeform: Boolean
)

class SkeletonBinary38(val atlas: TextureAtlas) {
    var scale: Float = 1.0f
    private val linkedMeshes = mutableListOf<LinkedMesh38>()

    fun readSkeletonData(file: FileHandle): SkeletonData38 {
        val bytes = file.readBytes()
        val data = SkeletonData38().apply { this.atlas = this@SkeletonBinary38.atlas }
        linkedMeshes.clear()

        // 1. Precisely detect whether binary stream has an 8-byte Long hash (3.8+) or a String hash (3.6 / 3.7)
        var isSpine38 = true
        var versionStr = "3.8"
        try {
            if (bytes.size > 12) {
                val testReader38 = BinaryStreamReader38(ByteArrayInputStream(bytes))
                testReader38.readLong() // 8 bytes hash
                val v38 = testReader38.readString()
                if (v38 != null && v38.length in 3..14 && (v38.startsWith("3.8") || v38.startsWith("4."))) {
                    isSpine38 = true
                    versionStr = v38
                } else {
                    val testReader37 = BinaryStreamReader38(ByteArrayInputStream(bytes))
                    testReader37.readString() // string hash in 3.6/3.7
                    val v37 = testReader37.readString()
                    if (v37 != null && v37.length in 3..14 && (v37.startsWith("3.7") || v37.startsWith("3.6") || v37.startsWith("3.5") || v37.startsWith("3."))) {
                        isSpine38 = false
                        versionStr = v37
                    }
                }
            }
        } catch (_: Exception) {
            isSpine38 = true
        }

        val reader = BinaryStreamReader38(ByteArrayInputStream(bytes))
        var nonessential = false
        if (isSpine38) {
            val hashLong = reader.readLong()
            val version = reader.readString() ?: "3.8.99"
            data.version = version
            data.hash = if (hashLong != 0L) hashLong.toString() else null
            data.x = reader.readFloat() * scale
            data.y = reader.readFloat() * scale
            data.width = reader.readFloat() * scale
            data.height = reader.readFloat() * scale
            nonessential = reader.readBoolean()
            if (nonessential) {
                data.fps = reader.readFloat()
                data.imagesPath = reader.readString()
                data.audioPath = reader.readString()
            }
        } else {
            data.hash = reader.readString()
            data.version = reader.readString() ?: versionStr
            data.width = reader.readFloat() * scale
            data.height = reader.readFloat() * scale
            nonessential = reader.readBoolean()
            if (nonessential) {
                data.fps = reader.readFloat()
                data.imagesPath = reader.readString()
            }
        }

        // Bones (Direct inline strings, no indexed table in 3.8/3.7)
        val boneCount = reader.readVarint(true)
        for (i in 0 until boneCount) {
            val name = reader.readString() ?: ""
            val parentIndex = if (i == 0) -1 else reader.readVarint(true)
            val parent = if (parentIndex in data.bones.indices) data.bones[parentIndex] else null
            val bone = BoneData38(i, name, parent).apply {
                rotation = reader.readFloat()
                x = reader.readFloat() * scale
                y = reader.readFloat() * scale
                scaleX = reader.readFloat()
                scaleY = reader.readFloat()
                shearX = reader.readFloat()
                shearY = reader.readFloat()
                length = reader.readFloat() * scale
                transformMode = TransformMode38.values()[reader.readVarint(true).coerceIn(0, 4)]
                skinRequired = if (isSpine38) reader.readBoolean() else false
                if (nonessential) reader.readInt() // color
            }
            data.bones.add(bone)
        }

        // Slots
        val slotCount = reader.readVarint(true)
        for (i in 0 until slotCount) {
            val slotName = reader.readString() ?: ""
            val boneIndex = reader.readVarint(true)
            val boneData = if (boneIndex in data.bones.indices) data.bones[boneIndex] else (data.bones.firstOrNull() ?: BoneData38(0, "root", null))
            val slotData = SlotData38(i, slotName, boneData).apply {
                val colorInt = reader.readInt()
                color.set(
                    ((colorInt ushr 24) and 0xFF) / 255f,
                    ((colorInt ushr 16) and 0xFF) / 255f,
                    ((colorInt ushr 8) and 0xFF) / 255f,
                    (colorInt and 0xFF) / 255f
                )
                val darkInt = reader.readInt()
                if (darkInt != -1) {
                    darkColor = Color(
                        ((darkInt ushr 16) and 0xFF) / 255f,
                        ((darkInt ushr 8) and 0xFF) / 255f,
                        (darkInt and 0xFF) / 255f,
                        1f
                    )
                }
                attachmentName = reader.readString()
                blendMode = BlendMode38.values()[reader.readVarint(true).coerceIn(0, 3)]
            }
            data.slots.add(slotData)
        }

        // IK Constraints
        val ikCount = reader.readVarint(true)
        for (i in 0 until ikCount) {
            val ik = IkConstraintData38(reader.readString() ?: "").apply {
                order = reader.readVarint(true)
                skinRequired = if (isSpine38) reader.readBoolean() else false
                val bonesSize = reader.readVarint(true)
                for (b in 0 until bonesSize) {
                    val bIdx = reader.readVarint(true)
                    if (bIdx in data.bones.indices) bones.add(data.bones[bIdx])
                }
                val targetIdx = reader.readVarint(true)
                target = if (targetIdx in data.bones.indices) data.bones[targetIdx] else (data.bones.firstOrNull() ?: BoneData38(0, "root", null))
                mix = reader.readFloat()
                softness = if (isSpine38) reader.readFloat() * scale else 0f
                bendDirection = reader.readByte().toInt()
                compress = if (isSpine38) reader.readBoolean() else false
                stretch = if (isSpine38) reader.readBoolean() else false
                uniform = if (isSpine38) reader.readBoolean() else false
            }
            data.ikConstraints.add(ik)
        }

        // Transform Constraints
        val tfCount = reader.readVarint(true)
        for (i in 0 until tfCount) {
            val tf = TransformConstraintData38(reader.readString() ?: "").apply {
                order = reader.readVarint(true)
                skinRequired = if (isSpine38) reader.readBoolean() else false
                val bonesSize = reader.readVarint(true)
                for (b in 0 until bonesSize) {
                    val bIdx = reader.readVarint(true)
                    if (bIdx in data.bones.indices) bones.add(data.bones[bIdx])
                }
                val targetIdx = reader.readVarint(true)
                target = if (targetIdx in data.bones.indices) data.bones[targetIdx] else (data.bones.firstOrNull() ?: BoneData38(0, "root", null))
                local = reader.readBoolean()
                relative = reader.readBoolean()
                offsetRotation = reader.readFloat()
                offsetX = reader.readFloat() * scale
                offsetY = reader.readFloat() * scale
                offsetScaleX = reader.readFloat()
                offsetScaleY = reader.readFloat()
                offsetShearY = reader.readFloat()
                rotateMix = reader.readFloat()
                translateMix = reader.readFloat()
                scaleMix = reader.readFloat()
                shearMix = reader.readFloat()
            }
            data.transformConstraints.add(tf)
        }

        // Path Constraints
        val pcCount = reader.readVarint(true)
        for (i in 0 until pcCount) {
            val pc = PathConstraintData38(reader.readString() ?: "").apply {
                order = reader.readVarint(true)
                skinRequired = if (isSpine38) reader.readBoolean() else false
                val bonesSize = reader.readVarint(true)
                for (b in 0 until bonesSize) {
                    val bIdx = reader.readVarint(true)
                    if (bIdx in data.bones.indices) bones.add(data.bones[bIdx])
                }
                val targetIdx = reader.readVarint(true)
                target = if (targetIdx in data.slots.indices) data.slots[targetIdx] else (data.slots.firstOrNull() ?: SlotData38(0, "root", data.bones.firstOrNull() ?: BoneData38(0, "root", null)))
                positionMode = reader.readVarint(true)
                spacingMode = reader.readVarint(true)
                rotateMode = reader.readVarint(true)
                offsetRotation = reader.readFloat()
                position = reader.readFloat()
                spacing = reader.readFloat()
                rotateMix = reader.readFloat()
                translateMix = reader.readFloat()
            }
            data.pathConstraints.add(pc)
        }

        // Default Skin
        val defaultSkin = readSkin(reader, "default", true, nonessential, isSpine38)
        data.defaultSkin = defaultSkin
        if (defaultSkin != null) data.skins.add(defaultSkin)

        // Other Skins
        val skinCount = reader.readVarint(true)
        for (i in 0 until skinCount) {
            val skin = readSkin(reader, "", false, nonessential, isSpine38)
            if (skin != null) data.skins.add(skin)
        }

        // Link Meshes
        for (lm in linkedMeshes) {
            val skin = if (lm.skinName == null) data.defaultSkin else (data.findSkin(lm.skinName) ?: data.defaultSkin)
            val parent = skin?.getAttachment(lm.slotIndex, lm.parentName ?: "") ?: data.defaultSkin?.getAttachment(lm.slotIndex, lm.parentName ?: "")
            if (parent is MeshAttachment38) {
                lm.mesh.bones = parent.bones
                lm.mesh.vertices = parent.vertices
                lm.mesh.worldVerticesLength = parent.worldVerticesLength
                lm.mesh.triangles = parent.triangles
                lm.mesh.regionUVs = parent.regionUVs
                lm.mesh.hullLength = parent.hullLength
                lm.mesh.updateUVs()
            }
        }

        // Events
        val eventCount = reader.readVarint(true)
        for (i in 0 until eventCount) {
            val ev = EventData38(reader.readString() ?: "").apply {
                intValue = reader.readVarint(false)
                floatValue = reader.readFloat()
                stringValue = reader.readRawString()
                audioPath = reader.readRawString()
                if (audioPath != null) {
                    volume = reader.readFloat()
                    balance = reader.readFloat()
                }
            }
            data.events.add(ev)
        }

        // Animations
        val animationCount = reader.readVarint(true)
        for (i in 0 until animationCount) {
            val animName = reader.readString() ?: "anim_$i"
            try {
                val anim = readAnimation(reader, animName, data)
                data.animations.add(anim)
            } catch (e: Exception) {
                data.animations.add(Animation38(animName, 1.0f))
            }
        }

        return data
    }

    private fun readSkin(reader: BinaryStreamReader38, skinName: String, isDefaultSkin: Boolean, nonessential: Boolean, isSpine38: Boolean): Skin38? {
        val skin: Skin38
        val slotCount: Int
        if (isSpine38) {
            if (isDefaultSkin) {
                slotCount = reader.readVarint(true)
                if (slotCount == 0) return null
                skin = Skin38("default")
            } else {
                val name = reader.readString() ?: skinName
                skin = Skin38(name)
                if (nonessential) {
                    reader.readInt() // skin color
                }
                val boneCount = reader.readVarint(true)
                for (i in 0 until boneCount) reader.readVarint(true)
                val ikCount = reader.readVarint(true)
                for (i in 0 until ikCount) reader.readVarint(true)
                val tfCount = reader.readVarint(true)
                for (i in 0 until tfCount) reader.readVarint(true)
                val pathCount = reader.readVarint(true)
                for (i in 0 until pathCount) reader.readVarint(true)
                slotCount = reader.readVarint(true)
            }
        } else {
            // Spine 3.6 / 3.7 skins (standard slot list format for both default and other skins)
            val name = if (isDefaultSkin) "default" else (reader.readString() ?: skinName)
            slotCount = reader.readVarint(true)
            if (slotCount == 0 && isDefaultSkin) return null
            skin = Skin38(name)
        }

        for (i in 0 until slotCount) {
            val slotIndex = reader.readVarint(true)
            val attachmentCount = reader.readVarint(true)
            for (ii in 0 until attachmentCount) {
                val placeholderName = reader.readString() ?: ""
                val attachment = readAttachment(reader, slotIndex, placeholderName, nonessential)
                if (attachment != null) {
                    skin.setAttachment(slotIndex, placeholderName, attachment)
                }
            }
        }
        return skin
    }

    private fun readAttachment(reader: BinaryStreamReader38, slotIndex: Int, defaultName: String, nonessential: Boolean): Attachment38? {
        val name = reader.readString() ?: defaultName
        val type = reader.readByte().toInt()
        when (type) {
            0 -> { // Region
                val path = reader.readString() ?: name
                val regionAtt = RegionAttachment38(name)
                regionAtt.path = path
                regionAtt.rotation = reader.readFloat()
                regionAtt.x = reader.readFloat() * scale
                regionAtt.y = reader.readFloat() * scale
                regionAtt.scaleX = reader.readFloat()
                regionAtt.scaleY = reader.readFloat()
                regionAtt.width = reader.readFloat() * scale
                regionAtt.height = reader.readFloat() * scale
                val colorInt = reader.readInt()
                regionAtt.color.set(
                    ((colorInt ushr 24) and 0xFF) / 255f,
                    ((colorInt ushr 16) and 0xFF) / 255f,
                    ((colorInt ushr 8) and 0xFF) / 255f,
                    (colorInt and 0xFF) / 255f
                )
                regionAtt.region = findRegionInAtlas(atlas, path, name)
                regionAtt.updateOffset()
                regionAtt.updateUVs()
                return regionAtt
            }
            1 -> { // BoundingBox
                val vertexCount = reader.readVarint(true)
                readVertices(reader, vertexCount)
                if (nonessential) reader.readInt()
                return null
            }
            2 -> { // Mesh
                val path = reader.readString() ?: name
                val colorInt = reader.readInt()
                val vertexCount = reader.readVarint(true)
                val uvs = FloatArray(vertexCount * 2)
                for (u in 0 until uvs.size) uvs[u] = reader.readFloat()

                val triangles = ShortArray(reader.readVarint(true))
                for (t in 0 until triangles.size) triangles[t] = reader.readShort()

                val (bones, verts) = readVertices(reader, vertexCount)
                val hullLength = reader.readVarint(true) * 2

                if (nonessential) {
                    val edgesCount = reader.readVarint(true)
                    for (e in 0 until edgesCount) reader.readShort()
                    reader.readFloat() // width
                    reader.readFloat() // height
                }

                val mesh = MeshAttachment38(name).apply {
                    this.path = path
                    this.color.set(
                        ((colorInt ushr 24) and 0xFF) / 255f,
                        ((colorInt ushr 16) and 0xFF) / 255f,
                        ((colorInt ushr 8) and 0xFF) / 255f,
                        (colorInt and 0xFF) / 255f
                    )
                    this.regionUVs = uvs
                    this.triangles = triangles
                    this.bones = bones
                    this.vertices = verts
                    this.worldVerticesLength = vertexCount * 2
                    this.hullLength = hullLength
                    this.region = findRegionInAtlas(atlas, path, name)
                    this.updateUVs()
                }
                return mesh
            }
            3 -> { // LinkedMesh
                val path = reader.readString() ?: name
                val colorInt = reader.readInt()
                val skinName = reader.readString()
                val parentName = reader.readString()
                val inheritDeform = reader.readBoolean()
                if (nonessential) {
                    reader.readFloat() // width
                    reader.readFloat() // height
                }
                val mesh = MeshAttachment38(name).apply {
                    this.path = path
                    this.color.set(
                        ((colorInt ushr 24) and 0xFF) / 255f,
                        ((colorInt ushr 16) and 0xFF) / 255f,
                        ((colorInt ushr 8) and 0xFF) / 255f,
                        (colorInt and 0xFF) / 255f
                    )
                    this.region = findRegionInAtlas(atlas, path, name)
                }
                linkedMeshes.add(LinkedMesh38(mesh, skinName, slotIndex, parentName, inheritDeform))
                return mesh
            }
            4 -> { // Path
                val closed = reader.readBoolean()
                val constantSpeed = reader.readBoolean()
                val vertexCount = reader.readVarint(true)
                readVertices(reader, vertexCount)
                val lengths = FloatArray(vertexCount / 3)
                for (l in 0 until lengths.size) lengths[l] = reader.readFloat() * scale
                if (nonessential) reader.readInt()
                return null
            }
            5 -> { // Point
                reader.readFloat() // x
                reader.readFloat() // y
                reader.readFloat() // rotation
                if (nonessential) reader.readInt()
                return null
            }
            6 -> { // Clipping
                val endSlotIndex = reader.readVarint(true)
                val vertexCount = reader.readVarint(true)
                readVertices(reader, vertexCount)
                if (nonessential) reader.readInt()
                return null
            }
        }
        return null
    }

    private fun readVertices(reader: BinaryStreamReader38, vertexCount: Int): Pair<IntArray?, FloatArray> {
        val verticesLength = vertexCount shl 1
        if (!reader.readBoolean()) {
            val verts = FloatArray(verticesLength)
            for (i in 0 until verticesLength) verts[i] = reader.readFloat() * scale
            return Pair(null, verts)
        }
        val weights = GdxFloatArray()
        val bonesArray = GdxIntArray()
        for (i in 0 until vertexCount) {
            val boneCount = reader.readVarint(true)
            bonesArray.add(boneCount)
            for (ii in 0 until boneCount) {
                bonesArray.add(reader.readVarint(true))
                weights.add(reader.readFloat() * scale)
                weights.add(reader.readFloat() * scale)
                weights.add(reader.readFloat())
            }
        }
        return Pair(bonesArray.toArray(), weights.toArray())
    }

    private fun readAnimation(reader: BinaryStreamReader38, name: String, skeletonData: SkeletonData38): Animation38 {
        var duration = 0f
        val animation = Animation38(name, 1f)

        // Slot timelines
        val slotCount = reader.readVarint(true)
        for (i in 0 until slotCount) {
            val slotIndex = reader.readVarint(true)
            val timelineCount = reader.readVarint(true)
            for (ii in 0 until timelineCount) {
                val timelineType = reader.readByte().toInt()
                val frameCount = reader.readVarint(true)
                when (timelineType) {
                    0 -> { // Attachment
                        val timeline = AttachmentTimeline38(frameCount)
                        timeline.slotIndex = slotIndex
                        for (frameIndex in 0 until frameCount) {
                            val time = reader.readFloat()
                            val attName = reader.readString()
                            timeline.setFrame(frameIndex, time, attName)
                        }
                        animation.timelines.add(timeline)
                        duration = maxOf(duration, timeline.frames[frameCount - 1])
                    }
                    1 -> { // Color
                        val timeline = ColorTimeline38(frameCount)
                        timeline.slotIndex = slotIndex
                        for (frameIndex in 0 until frameCount) {
                            val time = reader.readFloat()
                            val colorInt = reader.readInt()
                            timeline.setFrame(frameIndex, time, colorInt)
                            if (frameIndex < frameCount - 1) readCurve(reader, frameIndex, timeline)
                        }
                        animation.timelines.add(timeline)
                        duration = maxOf(duration, timeline.frames[(frameCount - 1) * 5])
                    }
                    2 -> { // TwoColor
                        for (frameIndex in 0 until frameCount) {
                            reader.readFloat(); reader.readInt(); reader.readInt()
                            if (frameIndex < frameCount - 1) readCurveStub(reader)
                        }
                    }
                }
            }
        }

        // Bone timelines
        val boneCount = reader.readVarint(true)
        for (i in 0 until boneCount) {
            val boneIndex = reader.readVarint(true)
            val timelineCount = reader.readVarint(true)
            for (ii in 0 until timelineCount) {
                val timelineType = reader.readByte().toInt()
                val frameCount = reader.readVarint(true)
                when (timelineType) {
                    0 -> { // Rotate
                        val timeline = RotateTimeline38(frameCount)
                        timeline.boneIndex = boneIndex
                        for (frameIndex in 0 until frameCount) {
                            val time = reader.readFloat()
                            val degrees = reader.readFloat()
                            timeline.setFrame(frameIndex, time, degrees)
                            if (frameIndex < frameCount - 1) readCurve(reader, frameIndex, timeline)
                        }
                        animation.timelines.add(timeline)
                        duration = maxOf(duration, timeline.frames[(frameCount - 1) * 2])
                    }
                    1, 2, 3 -> { // Translate, Scale, Shear
                        val timeline = if (timelineType == 1) TranslateTimeline38(frameCount) else ScaleTimeline38(frameCount)
                        if (timeline is TranslateTimeline38) timeline.boneIndex = boneIndex
                        if (timeline is ScaleTimeline38) timeline.boneIndex = boneIndex
                        for (frameIndex in 0 until frameCount) {
                            val time = reader.readFloat()
                            val x = reader.readFloat() * if (timelineType == 1) scale else 1f
                            val y = reader.readFloat() * if (timelineType == 1) scale else 1f
                            if (timeline is TranslateTimeline38) timeline.setFrame(frameIndex, time, x, y)
                            if (timeline is ScaleTimeline38) timeline.setFrame(frameIndex, time, x, y)
                            if (frameIndex < frameCount - 1) readCurve(reader, frameIndex, timeline)
                        }
                        animation.timelines.add(timeline)
                        val lastTime = if (timeline is TranslateTimeline38) timeline.frames[(frameCount - 1) * 3] else (timeline as ScaleTimeline38).frames[(frameCount - 1) * 3]
                        duration = maxOf(duration, lastTime)
                    }
                }
            }
        }

        // IK Timelines
        val ikCount = reader.readVarint(true)
        for (i in 0 until ikCount) {
            val ikIndex = reader.readVarint(true)
            val frameCount = reader.readVarint(true)
            for (f in 0 until frameCount) {
                reader.readFloat(); reader.readFloat(); reader.readFloat(); reader.readByte(); reader.readBoolean(); reader.readBoolean()
                if (f < frameCount - 1) readCurveStub(reader)
            }
        }

        // Transform Timelines
        val tfCount = reader.readVarint(true)
        for (i in 0 until tfCount) {
            val tfIndex = reader.readVarint(true)
            val frameCount = reader.readVarint(true)
            for (f in 0 until frameCount) {
                reader.readFloat(); reader.readFloat(); reader.readFloat(); reader.readFloat(); reader.readFloat()
                if (f < frameCount - 1) readCurveStub(reader)
            }
        }

        // Path Timelines
        val pathCount = reader.readVarint(true)
        for (i in 0 until pathCount) {
            val pathIndex = reader.readVarint(true)
            val timelineCount = reader.readVarint(true)
            for (ii in 0 until timelineCount) {
                val timelineType = reader.readByte().toInt()
                val frameCount = reader.readVarint(true)
                for (f in 0 until frameCount) {
                    if (timelineType == 2) {
                        reader.readFloat(); reader.readFloat(); reader.readFloat()
                    } else {
                        reader.readFloat(); reader.readFloat()
                    }
                    if (f < frameCount - 1) readCurveStub(reader)
                }
            }
        }

        // Deform timelines
        val deformCount = reader.readVarint(true)
        for (i in 0 until deformCount) {
            val skin = skeletonData.skins.getOrNull(reader.readVarint(true))
            val slotCountD = reader.readVarint(true)
            for (ii in 0 until slotCountD) {
                val slotIndex = reader.readVarint(true)
                val timelineCount = reader.readVarint(true)
                for (iii in 0 until timelineCount) {
                    val attachmentName = reader.readString() ?: ""
                    val frameCount = reader.readVarint(true)
                    val timeline = DeformTimeline38(frameCount)
                    timeline.slotIndex = slotIndex
                    timeline.skin = skin
                    for (frameIndex in 0 until frameCount) {
                        val time = reader.readFloat()
                        val end = reader.readVarint(true)
                        val vertices: FloatArray
                        if (end == 0) {
                            vertices = FloatArray(0)
                        } else {
                            val start = reader.readVarint(true)
                            vertices = FloatArray(start + end)
                            for (v in start until start + end) {
                                vertices[v] = reader.readFloat() * scale
                            }
                        }
                        timeline.setFrame(frameIndex, time, vertices)
                        if (frameIndex < frameCount - 1) readCurve(reader, frameIndex, timeline)
                    }
                    animation.timelines.add(timeline)
                    duration = maxOf(duration, timeline.frames[frameCount - 1])
                }
            }
        }

        // DrawOrder
        val drawOrderCount = reader.readVarint(true)
        if (drawOrderCount > 0) {
            for (i in 0 until drawOrderCount) {
                reader.readFloat()
                val offsetCount = reader.readVarint(true)
                for (ii in 0 until offsetCount) {
                    reader.readVarint(true); reader.readVarint(true)
                }
            }
        }

        // Events
        val eventCount = reader.readVarint(true)
        if (eventCount > 0) {
            for (i in 0 until eventCount) {
                reader.readFloat()
                val eventDataIndex = reader.readVarint(true)
                val eventData = skeletonData.events.getOrNull(eventDataIndex)
                reader.readVarint(false) // intValue
                reader.readFloat() // floatValue
                if (reader.readBoolean()) reader.readRawString() // stringValue
                if (eventData?.audioPath != null) {
                    reader.readFloat() // volume
                    reader.readFloat() // balance
                }
            }
        }

        return Animation38(name, if (duration <= 0f) 1.0f else duration).apply {
            this.timelines.addAll(animation.timelines)
        }
    }

    private fun readCurve(reader: BinaryStreamReader38, frameIndex: Int, timeline: CurveTimeline38) {
        when (reader.readByte().toInt()) {
            0 -> timeline.setLinear(frameIndex)
            1 -> timeline.setStepped(frameIndex)
            2 -> {
                reader.readFloat(); reader.readFloat(); reader.readFloat(); reader.readFloat()
                timeline.setLinear(frameIndex)
            }
        }
    }

    private fun readCurveStub(reader: BinaryStreamReader38) {
        when (reader.readByte().toInt()) {
            2 -> { reader.readFloat(); reader.readFloat(); reader.readFloat(); reader.readFloat() }
        }
    }
}

class ColorTimeline38(frameCount: Int) : CurveTimeline38(frameCount) {
    var slotIndex: Int = 0
    val frames = FloatArray(frameCount * 5) // time, r, g, b, a

    fun setFrame(frameIndex: Int, time: Float, colorInt: Int) {
        val idx = frameIndex * 5
        frames[idx] = time
        frames[idx + 1] = ((colorInt ushr 24) and 0xFF) / 255f
        frames[idx + 2] = ((colorInt ushr 16) and 0xFF) / 255f
        frames[idx + 3] = ((colorInt ushr 8) and 0xFF) / 255f
        frames[idx + 4] = (colorInt and 0xFF) / 255f
    }

    override fun apply(skeleton: Skeleton38, lastTime: Float, time: Float, alpha: Float, blend: MixBlend38, direction: MixDirection38) {
        val slot = skeleton.slots[slotIndex]
        val frames = this.frames
        if (time < frames[0]) {
            if (blend == MixBlend38.setup) slot.color.set(slot.data.color)
            return
        }
        if (time >= frames[frames.size - 5]) {
            val i = frames.size - 5
            slot.color.set(frames[i + 1], frames[i + 2], frames[i + 3], frames[i + 4])
            return
        }

        val frame = binarySearch(frames, time, 5)
        val prevR = frames[frame - 4]
        val prevG = frames[frame - 3]
        val prevB = frames[frame - 2]
        val prevA = frames[frame - 1]
        val frameTime = frames[frame]
        val percent = getCurvePercent(frame / 5 - 1, 1f - (time - frameTime) / (frames[frame - 5] - frameTime))

        val r = prevR + (frames[frame + 1] - prevR) * percent
        val g = prevG + (frames[frame + 2] - prevG) * percent
        val b = prevB + (frames[frame + 3] - prevB) * percent
        val a = prevA + (frames[frame + 4] - prevA) * percent

        if (alpha < 1f) {
            slot.color.add((r - slot.color.r) * alpha, (g - slot.color.g) * alpha, (b - slot.color.b) * alpha, (a - slot.color.a) * alpha)
        } else {
            slot.color.set(r, g, b, a)
        }
    }
}

class BinaryStreamReader38(private val input: InputStream) {

    fun readByte(): Byte {
        val b = input.read()
        if (b == -1) throw EOFException()
        return b.toByte()
    }

    fun readBoolean(): Boolean = readByte().toInt() != 0

    fun readShort(): Short {
        val b1 = input.read()
        val b2 = input.read()
        if ((b1 or b2) < 0) throw EOFException()
        return ((b1 shl 8) or b2).toShort()
    }

    fun readInt(): Int {
        val b1 = input.read()
        val b2 = input.read()
        val b3 = input.read()
        val b4 = input.read()
        if ((b1 or b2 or b3 or b4) < 0) throw EOFException()
        return (b1 shl 24) or (b2 shl 16) or (b3 shl 8) or b4
    }

    fun readLong(): Long {
        var result = 0L
        for (i in 0 until 8) {
            val b = input.read()
            if (b == -1) throw EOFException()
            result = (result shl 8) or (b.toLong() and 0xFFL)
        }
        return result
    }

    fun readFloat(): Float {
        return java.lang.Float.intBitsToFloat(readInt())
    }

    fun readVarint(optimizePositive: Boolean): Int {
        var b = input.read()
        if (b == -1) throw EOFException()
        var result = b and 0x7F
        if ((b and 0x80) != 0) {
            b = input.read()
            if (b == -1) throw EOFException()
            result = result or ((b and 0x7F) shl 7)
            if ((b and 0x80) != 0) {
                b = input.read()
                if (b == -1) throw EOFException()
                result = result or ((b and 0x7F) shl 14)
                if ((b and 0x80) != 0) {
                    b = input.read()
                    if (b == -1) throw EOFException()
                    result = result or ((b and 0x7F) shl 21)
                    if ((b and 0x80) != 0) {
                        b = input.read()
                        if (b == -1) throw EOFException()
                        result = result or ((b and 0x7F) shl 28)
                    }
                }
            }
        }
        return if (optimizePositive) result else ((result ushr 1) xor -(result and 1))
    }

    fun readString(): String? {
        val length = readVarint(true)
        if (length == 0) return null
        if (length == 1) return ""
        val byteCount = length - 1
        if (byteCount < 0 || byteCount > 10 * 1024 * 1024) return null
        val bytes = ByteArray(byteCount)
        var total = 0
        while (total < bytes.size) {
            val count = input.read(bytes, total, bytes.size - total)
            if (count == -1) throw EOFException()
            total += count
        }
        return String(bytes, StandardCharsets.UTF_8)
    }

    fun readRawString(): String? = readString()
}

// ==========================================
// 8. SKELETON JSON PARSER (Spine 3.6 / 3.7 / 3.8)
// ==========================================

class SkeletonJson38(val atlas: TextureAtlas) {
    var scale: Float = 1.0f

    fun readSkeletonData(file: FileHandle): SkeletonData38 {
        val rawJson = file.readString("UTF-8")
        val root = org.json.JSONObject(rawJson)
        val data = SkeletonData38().apply { this.atlas = this@SkeletonJson38.atlas }

        // Skeleton Header
        val skeletonObj = root.optJSONObject("skeleton")
        if (skeletonObj != null) {
            data.hash = skeletonObj.optString("hash", null)
            data.version = skeletonObj.optString("spine", "3.8")
            data.x = skeletonObj.optDouble("x", 0.0).toFloat() * scale
            data.y = skeletonObj.optDouble("y", 0.0).toFloat() * scale
            data.width = skeletonObj.optDouble("width", 0.0).toFloat() * scale
            data.height = skeletonObj.optDouble("height", 0.0).toFloat() * scale
            data.fps = skeletonObj.optDouble("fps", 30.0).toFloat()
            data.imagesPath = skeletonObj.optString("images", null)
            data.audioPath = skeletonObj.optString("audio", null)
        }

        // Bones
        val bonesArr = root.optJSONArray("bones")
        if (bonesArr != null) {
            for (i in 0 until bonesArr.length()) {
                val bObj = bonesArr.getJSONObject(i)
                val name = bObj.getString("name")
                val parentName = bObj.optString("parent", null)
                val parent = if (parentName != null) data.bones.find { it.name == parentName } else null
                val bone = BoneData38(i, name, parent).apply {
                    length = bObj.optDouble("length", 0.0).toFloat() * scale
                    x = bObj.optDouble("x", 0.0).toFloat() * scale
                    y = bObj.optDouble("y", 0.0).toFloat() * scale
                    rotation = bObj.optDouble("rotation", 0.0).toFloat()
                    scaleX = bObj.optDouble("scaleX", 1.0).toFloat()
                    scaleY = bObj.optDouble("scaleY", 1.0).toFloat()
                    shearX = bObj.optDouble("shearX", 0.0).toFloat()
                    shearY = bObj.optDouble("shearY", 0.0).toFloat()
                }
                data.bones.add(bone)
            }
        }

        // Slots
        val slotsArr = root.optJSONArray("slots")
        if (slotsArr != null) {
            for (i in 0 until slotsArr.length()) {
                val sObj = slotsArr.getJSONObject(i)
                val slotName = sObj.getString("name")
                val boneName = sObj.getString("bone")
                val boneData = data.bones.find { it.name == boneName } ?: data.bones[0]
                val slot = SlotData38(i, slotName, boneData).apply {
                    attachmentName = sObj.optString("attachment", null)
                    val colorHex = sObj.optString("color", null)
                    if (colorHex != null && colorHex.length >= 6) {
                        color.set(Color.valueOf(colorHex))
                    }
                    val blendStr = sObj.optString("blend", "normal")
                    blendMode = try { BlendMode38.valueOf(blendStr) } catch (_: Exception) { BlendMode38.normal }
                }
                data.slots.add(slot)
            }
        }

        // Skins: Support BOTH Spine 3.6/3.7 (Object format) AND Spine 3.8 (Array format)
        val skinsObj = root.optJSONObject("skins")
        val skinsArr = root.optJSONArray("skins")

        fun parseSkinSlots(skin: Skin38, slotsMap: org.json.JSONObject) {
            val slotKeys = slotsMap.keys()
            while (slotKeys.hasNext()) {
                val slotName = slotKeys.next()
                val slotData = data.slots.find { it.name == slotName } ?: continue
                val attsObj = slotsMap.optJSONObject(slotName) ?: continue
                val attKeys = attsObj.keys()
                while (attKeys.hasNext()) {
                    val attName = attKeys.next()
                    val attObj = attsObj.optJSONObject(attName) ?: continue
                    val attType = attObj.optString("type", "region")
                    val path = attObj.optString("path", attName)

                    if (attType == "region") {
                        val reg = RegionAttachment38(attName).apply {
                            this.path = path
                            x = attObj.optDouble("x", 0.0).toFloat() * scale
                            y = attObj.optDouble("y", 0.0).toFloat() * scale
                            scaleX = attObj.optDouble("scaleX", 1.0).toFloat()
                            scaleY = attObj.optDouble("scaleY", 1.0).toFloat()
                            rotation = attObj.optDouble("rotation", 0.0).toFloat()
                            width = attObj.optDouble("width", 32.0).toFloat() * scale
                            height = attObj.optDouble("height", 32.0).toFloat() * scale
                            region = findRegionInAtlas(atlas, path, attName)
                            updateOffset()
                            updateUVs()
                        }
                        skin.setAttachment(slotData.index, attName, reg)
                    } else if (attType == "mesh" || attType == "linkedmesh") {
                        val uvsArr = attObj.optJSONArray("uvs")
                        val trisArr = attObj.optJSONArray("triangles")
                        val vertsArr = attObj.optJSONArray("vertices")
                        if (uvsArr != null && trisArr != null && vertsArr != null) {
                            val uvs = FloatArray(uvsArr.length()) { uvsArr.getDouble(it).toFloat() }
                            val tris = ShortArray(trisArr.length()) { trisArr.getInt(it).toShort() }
                            val rawVerts = FloatArray(vertsArr.length()) { vertsArr.getDouble(it).toFloat() }
                            
                            val mesh = MeshAttachment38(attName).apply {
                                this.path = path
                                this.regionUVs = uvs
                                this.triangles = tris
                                this.worldVerticesLength = uvs.size
                                this.region = findRegionInAtlas(atlas, path, attName)
                            }

                            if (rawVerts.size == uvs.size) {
                                // Unweighted mesh: vertices are pure x,y coordinates
                                mesh.bones = null
                                mesh.vertices = FloatArray(rawVerts.size) { rawVerts[it] * scale }
                            } else {
                                // Weighted mesh: [boneCount, boneIndex1, x1, y1, weight1, ...]
                                val bonesList = GdxIntArray()
                                val weightsList = GdxFloatArray()
                                var i = 0
                                while (i < rawVerts.size) {
                                    val boneCount = rawVerts[i++].toInt()
                                    bonesList.add(boneCount)
                                    for (b in 0 until boneCount) {
                                        if (i >= rawVerts.size) break
                                        bonesList.add(rawVerts[i++].toInt())
                                        val vx = if (i < rawVerts.size) rawVerts[i++] else 0f
                                        val vy = if (i < rawVerts.size) rawVerts[i++] else 0f
                                        val wt = if (i < rawVerts.size) rawVerts[i++] else 1f
                                        weightsList.add(vx * scale)
                                        weightsList.add(vy * scale)
                                        weightsList.add(wt)
                                    }
                                }
                                mesh.bones = bonesList.toArray()
                                mesh.vertices = weightsList.toArray()
                            }

                            mesh.updateUVs()
                            skin.setAttachment(slotData.index, attName, mesh)
                        }
                    }
                }
            }
        }

        if (skinsArr != null) {
            // Spine 3.8 array style: [ { "name": "default", "attachments": { ... } } ]
            for (s in 0 until skinsArr.length()) {
                val skinItem = skinsArr.optJSONObject(s) ?: continue
                val skinName = skinItem.optString("name", "default")
                val skin = Skin38(skinName)
                val attsMap = skinItem.optJSONObject("attachments")
                if (attsMap != null) {
                    parseSkinSlots(skin, attsMap)
                }
                data.skins.add(skin)
                if (skinName == "default") {
                    data.defaultSkin = skin
                }
            }
        } else if (skinsObj != null) {
            // Spine 3.6 / 3.7 object style: { "default": { "slot1": { ... } } }
            val skinKeys = skinsObj.keys()
            while (skinKeys.hasNext()) {
                val skinName = skinKeys.next()
                val skinSlotsObj = skinsObj.optJSONObject(skinName) ?: continue
                val skin = Skin38(skinName)
                parseSkinSlots(skin, skinSlotsObj)
                data.skins.add(skin)
                if (skinName == "default") {
                    data.defaultSkin = skin
                }
            }
        }

        // Default Skin Assignment
        if (data.defaultSkin == null && data.skins.isNotEmpty()) {
            data.defaultSkin = data.skins.first()
        }

        // Animations
        val animsObj = root.optJSONObject("animations")
        if (animsObj != null) {
            val animKeys = animsObj.keys()
            while (animKeys.hasNext()) {
                val animName = animKeys.next()
                try {
                    val animObj = animsObj.optJSONObject(animName) ?: continue
                    val anim = Animation38(animName, 1.0f)
                    var maxDuration = 0f

                    // Bone timelines
                    val bonesAnimObj = animObj.optJSONObject("bones")
                    if (bonesAnimObj != null) {
                        val boneKeys = bonesAnimObj.keys()
                        while (boneKeys.hasNext()) {
                            val bName = boneKeys.next()
                            val boneData = data.bones.find { it.name == bName } ?: continue
                            val bTimelineObj = bonesAnimObj.optJSONObject(bName) ?: continue

                            val rotateArr = bTimelineObj.optJSONArray("rotate")
                            if (rotateArr != null && rotateArr.length() > 0) {
                                val timeline = RotateTimeline38(rotateArr.length()).apply { boneIndex = boneData.index }
                                for (f in 0 until rotateArr.length()) {
                                    val frame = rotateArr.optJSONObject(f) ?: continue
                                    val time = frame.optDouble("time", 0.0).toFloat()
                                    val angle = frame.optDouble("angle", frame.optDouble("value", 0.0)).toFloat()
                                    timeline.setFrame(f, time, angle)
                                    maxDuration = maxOf(maxDuration, time)
                                }
                                anim.timelines.add(timeline)
                            }

                            val translateArr = bTimelineObj.optJSONArray("translate")
                            if (translateArr != null && translateArr.length() > 0) {
                                val timeline = TranslateTimeline38(translateArr.length()).apply { boneIndex = boneData.index }
                                for (f in 0 until translateArr.length()) {
                                    val frame = translateArr.optJSONObject(f) ?: continue
                                    val time = frame.optDouble("time", 0.0).toFloat()
                                    val x = frame.optDouble("x", 0.0).toFloat() * scale
                                    val y = frame.optDouble("y", 0.0).toFloat() * scale
                                    timeline.setFrame(f, time, x, y)
                                    maxDuration = maxOf(maxDuration, time)
                                }
                                anim.timelines.add(timeline)
                            }

                            val scaleArr = bTimelineObj.optJSONArray("scale")
                            if (scaleArr != null && scaleArr.length() > 0) {
                                val timeline = ScaleTimeline38(scaleArr.length()).apply { boneIndex = boneData.index }
                                for (f in 0 until scaleArr.length()) {
                                    val frame = scaleArr.optJSONObject(f) ?: continue
                                    val time = frame.optDouble("time", 0.0).toFloat()
                                    val x = frame.optDouble("x", 1.0).toFloat()
                                    val y = frame.optDouble("y", 1.0).toFloat()
                                    timeline.setFrame(f, time, x, y)
                                    maxDuration = maxOf(maxDuration, time)
                                }
                                anim.timelines.add(timeline)
                            }
                        }
                    }

                    // Slot timelines
                    val slotsAnimObj = animObj.optJSONObject("slots")
                    if (slotsAnimObj != null) {
                        val slotKeys = slotsAnimObj.keys()
                        while (slotKeys.hasNext()) {
                            val sName = slotKeys.next()
                            val slotData = data.slots.find { it.name == sName } ?: continue
                            val sTimelineObj = slotsAnimObj.optJSONObject(sName) ?: continue

                            val attArr = sTimelineObj.optJSONArray("attachment")
                            if (attArr != null && attArr.length() > 0) {
                                val timeline = AttachmentTimeline38(attArr.length()).apply { slotIndex = slotData.index }
                                for (f in 0 until attArr.length()) {
                                    val frame = attArr.optJSONObject(f) ?: continue
                                    val time = frame.optDouble("time", 0.0).toFloat()
                                    val attName = frame.optString("name", null)
                                    timeline.setFrame(f, time, attName)
                                    maxDuration = maxOf(maxDuration, time)
                                }
                                anim.timelines.add(timeline)
                            }
                        }
                    }

                    data.animations.add(Animation38(animName, if (maxDuration > 0f) maxDuration else 1.0f).apply {
                        timelines.addAll(anim.timelines)
                    })
                } catch (e: Exception) {
                    data.animations.add(Animation38(animName, 1.0f))
                }
            }
        }

        return data
    }
}

