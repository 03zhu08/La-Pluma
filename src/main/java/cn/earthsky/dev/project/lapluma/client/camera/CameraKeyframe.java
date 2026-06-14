package cn.earthsky.dev.project.lapluma.client.camera;

import net.minecraft.util.math.Vec3d;

public class CameraKeyframe {
    public int time;
    public Vec3d position = Vec3d.ZERO;
    public float pitch;
    public float yaw;
    public float roll;
    public Float fov;
    public CameraLens lens;
    public Vec3d lookAt;

    public CameraKeyframe copy() {
        CameraKeyframe keyframe = new CameraKeyframe();
        keyframe.time = time;
        keyframe.position = position;
        keyframe.pitch = pitch;
        keyframe.yaw = yaw;
        keyframe.roll = roll;
        keyframe.fov = fov;
        keyframe.lens = lens == null ? null : lens.copy();
        keyframe.lookAt = lookAt;
        return keyframe;
    }
}
