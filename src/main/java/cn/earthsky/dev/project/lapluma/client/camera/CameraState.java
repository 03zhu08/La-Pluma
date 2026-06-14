package cn.earthsky.dev.project.lapluma.client.camera;

import net.minecraft.util.math.Vec3d;

public class CameraState {
    public Vec3d position = Vec3d.ZERO;
    public float pitch;
    public float yaw;
    public float roll;
    public float fov = 70.0f;
    public CameraLens lens = new CameraLens();
}
