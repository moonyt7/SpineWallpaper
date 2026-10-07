package com.spine.wallpaper.bridge

/**
 * Supported Spine runtime major versions in the multi-runtime isolated environment.
 */
enum class SpineVersion(
    val versionString: String,
    val displayName: String,
    val moduleName: String,
    val description: String
) {
    V36("3.6", "Spine 3.5 / 3.6 (老格式)", ":spine-runtime-v36", "官方 3.6 runtime，兼容读取 3.5 导出的老 skins 字典格式数据"),
    V37("3.7", "Spine 3.7 (老格式)", ":spine-runtime-v37", "官方 3.7 runtime，3.7 JSON 仍为老 skins 字典格式，3.8 runtime 无法解析"),
    V38("3.8", "Spine 3.8 (主流游戏通用)", ":spine-runtime-v38", "明日方舟、蔚蓝档案、碧蓝航线等主流二次元手游标准格式"),
    V40("4.0", "Spine 4.0 (过渡版本)", ":spine-runtime-v40", "支持曲线插值优化与多边形附件新结构"),
    V41("4.1", "Spine 4.1 (现代官方标准)", ":spine-runtime-v41", "Spine 4.1 序列帧附件与高效图集渲染"),
    V42("4.2", "Spine 4.2 (次时代物理引擎)", ":spine-runtime-v42", "支持实时物理约束、多轨道时间轴与分离缩放"),
    V43("4.3", "Spine 4.3 (最新标准)", ":spine-runtime-v43", "贴图 UV 移入 Sequence、附件加载 placeholder 化、setupPose 统一接口"),
    V43B("4.3.39-beta", "Spine 4.3 beta (4.3.39 中间格式)", ":spine-runtime-v43b", "dev 窗口中间格式：附件 loader 无 placeholder、readVertices 为旧版。已发布的 4.2.12 / 4.3.0~4.3.5 都读不了，必须用这一版"),
    UNKNOWN("unknown", "自动探测 / 自适应模式", ":spine-core-bridge", "通过魔数嗅探器自动推断并降级容错");

    companion object {
        fun fromString(versionStr: String?): SpineVersion {
            if (versionStr.isNullOrEmpty()) return UNKNOWN
            val clean = versionStr.trim().lowercase()
            return when {
                clean.startsWith("3.5") || clean.startsWith("3.6") -> V36
                clean.startsWith("3.7") -> V37
                clean.startsWith("3.8") -> V38
                clean.startsWith("4.0") -> V40
                clean.startsWith("4.1") -> V41
                clean.startsWith("4.2") -> V42
                // ⚠️ 4.3 有两个互不兼容的「窗口」，靠版本串区分，不能只看 "4.3" 前缀：
                //   · 已发布档（4.3.0 ~ 4.3.5）           → V43
                //   · dev 窗口的中间格式（4.3.39-beta 等）  → V43B
                // 后者骨骼核心顺序是 4.3 的，但 readVertices 为旧版、尾部无 iconSize/iconRotation，
                // 已发布 runtime 一律读不了。区分规则：带 "-beta" 后缀即 dev 窗口。
                // 这只是「首选猜测」，真正的判据仍是运行时的逐级降级（试不通自然换下一档），
                // 所以万一这里猜错也不会误伤 —— 但猜对了能少跑一次注定失败的解析。
                clean.startsWith("4.3") && clean.contains("beta") -> V43B
                clean.startsWith("4.3") -> V43
                else -> UNKNOWN
            }
        }

        /** 由 major/minor 构造版本（如 4.2 -> V42，3.8 -> V38），未知返回 UNKNOWN */
        fun fromMajorMinor(major: Int, minor: Int): SpineVersion {
            return fromString("$major.$minor")
        }
    }
}