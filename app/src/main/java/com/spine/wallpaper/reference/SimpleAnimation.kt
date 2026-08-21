package com.spine.wallpaper.reference

import com.esotericsoftware.spine.*

/**
 * Reference code pattern for SimpleAnimation in spine-android runtime.
 */
class SimpleAnimationSample {

    fun setupSpineAnimation(
        skeletonData: SkeletonData,
        animationName: String = "walk"
    ): Pair<Skeleton, AnimationState> {
        val skeleton = Skeleton(skeletonData)
        val stateData = AnimationStateData(skeletonData)
        val state = AnimationState(stateData)

        // Set track 0 animation looping
        state.setAnimation(0, animationName, true)

        return Pair(skeleton, state)
    }

    fun renderLoop(
        deltaSeconds: Float,
        skeleton: Skeleton,
        state: AnimationState
    ) {
        state.update(deltaSeconds)
        state.apply(skeleton)
        skeleton.updateWorldTransform()
    }
}