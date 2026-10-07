package com.esotericsoftware.spine;

/**
 * 4.3.39-beta（dev 窗口）运行时的「同包访问助手」。
 *
 * 为什么需要它：
 * 这一版 runtime 把大量成员改成了**包私有且没有 getter**（`Skeleton.x/y/skin/scaleX`、
 * `SkeletonData.width/height/version`、`AnimationStateData.defaultMix`、
 * `Skin.attachments/bones/constraints`…）。
 *
 * 本类**故意与运行时代码放在同一个 Java 包**（`com.esotericsoftware.spine`）——
 * 经 shadow `relocate` 后仍与 runtime 落在同一个包（`com.spine.wallpaper.spine43b`），
 * 所以可以直接读写这些包私有字段：**零反射开销、无 Java 版本限制**。
 *
 * ⚠️ 这里只做**字段读**和**公开 API 调用**。任何「反射写 final 字段」的写法都不要加进来：
 * 桌面 JVM 上有效，但 Android ART 上会被常量折叠掉，表现为「赋值成功、后面读到的还是旧值」。
 */
public final class V43bSupport {

    private V43bSupport () {
    }

    // ==================== Skeleton ====================

    /** 这一版没有 `setPosition`；`x`/`y` 是包私有字段。 */
    public static void setPosition (Skeleton skeleton, float x, float y) {
        skeleton.x = x;
        skeleton.y = y;
    }

    /** 这一版没有 `getSkin()`。 */
    public static Skin getSkin (Skeleton skeleton) {
        return skeleton.skin;
    }

    // ==================== SkeletonData ====================

    /** 这一版没有 `getWidth()/getHeight()`，只有包私有字段。 */
    public static float getWidth (SkeletonData data) {
        return data.width;
    }

    public static float getHeight (SkeletonData data) {
        return data.height;
    }

    public static String getVersion (SkeletonData data) {
        return data.version;
    }

    // ==================== Skin 合并 ====================

    /**
     * 4.3.5 有 `Skin.addSkin(Skin)`，这一版被撤了（只留包私有的 `attachAll(Skeleton, Skin)`）。
     * 这里用公开的 `setAttachment` + 包私有的 `attachments/bones/constraints` 重新实现合并，
     * 语义与 4.3.5 的 `addSkin` 一致：**后者覆盖同名 slot+attachment**。
     */
    public static Skin combineSkins (Skin first, Skin second) {
        Skin out = new Skin("__combined__");
        merge(out, first);
        merge(out, second);
        return out;
    }

    private static void merge (Skin dst, Skin src) {
        if (src == null) return;
        for (Skin.SkinEntry entry : src.attachments) {
            if (entry.attachment != null) dst.setAttachment(entry.slotIndex, entry.name, entry.attachment);
        }
        for (BoneData data : src.bones) {
            if (!dst.bones.contains(data, true)) dst.bones.add(data);
        }
        for (ConstraintData data : src.constraints) {
            if (!dst.constraints.contains(data, true)) dst.constraints.add(data);
        }
    }
}
