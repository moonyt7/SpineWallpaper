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
                clean.startsWith("4.2") || clean.startsWith("4.3") -> V42
                else -> UNKNOWN
            }
        }

        /** 由 major/minor 构造版本（如 4.2 -> V42，3.8 -> V38），未知返回 UNKNOWN */
        fun fromMajorMinor(major: Int, minor: Int): SpineVersion {
            return fromString("$major.$minor")
        }
    }
}