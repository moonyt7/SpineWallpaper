package com.esotericsoftware.spine;

/**
 * Spine 2.1 runtime 的同包辅助类。
 *
 * 2.1 的 {@link Bone#scaleX} / {@link Bone#scaleY} 是**包私有字段**（没有 getter/setter），
 * 外部无法缩放根骨骼 —— 而 2.1 的 {@code Skeleton} 没有整体缩放 API，只能改根骨骼。
 * 把这个类故意放在 {@code com.esotericsoftware.spine} 包里，就零反射读写它。
 *
 * 本类随 shadow relocate 一起被搬到 {@code com.spine.wallpaper.spine21}，
 * 与重定位后的 Bone 仍在同一个包 ⇒ 访问权限依然成立。
 */
public final class V21Support {

	private V21Support () {
	}

	/** 读取根骨骼的 setup 缩放 X（2.1 没有 getter，只能同包直读字段）。 */
	public static float getRootScaleX (Skeleton skeleton) {
		Bone root = skeleton == null ? null : skeleton.getRootBone();
		return root == null ? 1f : root.scaleX;
	}

	/** 读取根骨骼的 setup 缩放 Y。 */
	public static float getRootScaleY (Skeleton skeleton) {
		Bone root = skeleton == null ? null : skeleton.getRootBone();
		return root == null ? 1f : root.scaleY;
	}

	/** 每帧渲染前写入根骨骼缩放（2.1 没有 setter，只能同包直写字段）。 */
	public static void setRootScale (Skeleton skeleton, float scaleX, float scaleY) {
		Bone root = skeleton == null ? null : skeleton.getRootBone();
		if (root == null) return;
		root.scaleX = scaleX;
		root.scaleY = scaleY;
	}
}
