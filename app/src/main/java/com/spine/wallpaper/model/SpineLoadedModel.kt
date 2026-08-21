package com.spine.wallpaper.model

import com.esotericsoftware.spine.AnimationState
import com.esotericsoftware.spine.AnimationStateData
import com.esotericsoftware.spine.Skeleton
import com.esotericsoftware.spine.SkeletonData

data class SpineLoadedModel(
    val skeletonData: SkeletonData,
    val skeleton: Skeleton,
    val animationState: AnimationState,
    val animationStateData: AnimationStateData,
    val config: Live2DConfig? = null
)