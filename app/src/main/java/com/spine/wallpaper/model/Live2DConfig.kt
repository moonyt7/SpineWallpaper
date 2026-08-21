package com.spine.wallpaper.model

import org.json.JSONObject

data class Live2DMotion(
    val file: String,
    val track: Int = 0,
    val loop: Boolean = true,
    val name: String? = null
)

data class Live2DConfig(
    val version: String = "1.0.0",
    val model: String = "",
    val atlas: String = "",
    val name: String? = null,
    val scale: Float = 1.0f,
    val posX: Float = 0.0f,
    val posY: Float = 0.0f,
    val idle_motion: String? = "idle",
    val tap_motions: Map<String, String> = emptyMap(),
    val motions: List<Live2DMotion> = emptyList(),
    val skins: List<String> = emptyList()
) {
    companion object {
        fun parse(jsonString: String): Live2DConfig {
            val json = JSONObject(jsonString)
            val pos = json.optJSONObject("position")

            val tapMap = mutableMapOf<String, String>()
            json.optJSONObject("tap_motions")?.let { tapObj ->
                tapObj.keys().forEach { key ->
                    tapMap[key] = tapObj.getString(key)
                }
            }

            val motionList = mutableListOf<Live2DMotion>()
            json.optJSONArray("motions")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val m = arr.getJSONObject(i)
                    motionList.add(
                        Live2DMotion(
                            file = m.getString("file"),
                            track = m.optInt("track", 0),
                            loop = m.optBoolean("loop", true),
                            name = m.optString("name", null)
                        )
                    )
                }
            }

            val skinsList = mutableListOf<String>()
            json.optJSONArray("skins")?.let { arr ->
                for (i in 0 until arr.length()) {
                    skinsList.add(arr.getString(i))
                }
            }

            return Live2DConfig(
                version = json.optString("version", "1.0.0"),
                model = json.optString("model", ""),
                atlas = json.optString("atlas", ""),
                name = json.optString("name", null),
                scale = json.optDouble("scale", 1.0).toFloat(),
                posX = pos?.optDouble("x", 0.0)?.toFloat() ?: 0.0f,
                posY = pos?.optDouble("y", 0.0)?.toFloat() ?: 0.0f,
                idle_motion = json.optString("idle_motion", "idle"),
                tap_motions = tapMap,
                motions = motionList,
                skins = skinsList
            )
        }
    }
}