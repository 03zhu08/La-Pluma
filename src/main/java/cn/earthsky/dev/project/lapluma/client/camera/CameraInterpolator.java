package cn.earthsky.dev.project.lapluma.client.camera;

import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import java.util.List;

public class CameraInterpolator {

    public static CameraState sample(CameraDefinition definition, int tick) {
        CameraState state = new CameraState();
        CameraKeyframe transform = sampleKeyframe(definition.keyframes, tick);
        state.position = transform.position;
        state.pitch = transform.pitch;
        state.yaw = transform.yaw;
        state.roll = transform.roll;
        state.lens = transform.lens == null ? new CameraLens() : transform.lens.copy();

        CameraKeyframe lensFrame = sampleKeyframe(definition.lensTrack, tick);
        if (lensFrame != null && lensFrame.lens != null) {
            state.lens = lensFrame.lens.copy();
        }

        state.fov = focalLengthToFov(state.lens.focalLength);
        CameraKeyframe fovFrame = sampleKeyframe(definition.fovTrack, tick);
        if (fovFrame != null && fovFrame.fov != null) {
            state.fov = fovFrame.fov;
        } else if (transform.fov != null) {
            state.fov = transform.fov;
        }

        Vec3d lookAt = transform.lookAt;
        if (lookAt != null) {
            applyLookAt(state, lookAt);
        }
        return state;
    }

    private static CameraKeyframe sampleKeyframe(List<CameraKeyframe> keyframes, int tick) {
        if (keyframes == null || keyframes.isEmpty()) return null;
        if (keyframes.size() == 1 || tick <= keyframes.get(0).time) return keyframes.get(0).copy();
        CameraKeyframe last = keyframes.get(keyframes.size() - 1);
        if (tick >= last.time) return last.copy();

        CameraKeyframe a = keyframes.get(0);
        CameraKeyframe b = last;
        for (int i = 0; i < keyframes.size() - 1; i++) {
            CameraKeyframe left = keyframes.get(i);
            CameraKeyframe right = keyframes.get(i + 1);
            if (tick >= left.time && tick <= right.time) {
                a = left;
                b = right;
                break;
            }
        }
        float span = Math.max(1.0f, b.time - a.time);
        float t = smooth((tick - a.time) / span);
        CameraKeyframe out = new CameraKeyframe();
        out.time = tick;
        out.position = lerp(a.position, b.position, t);
        out.pitch = lerpAngle(a.pitch, b.pitch, t);
        out.yaw = lerpAngle(a.yaw, b.yaw, t);
        out.roll = lerpAngle(a.roll, b.roll, t);
        out.fov = a.fov == null && b.fov == null ? null : lerp(a.fov == null ? b.fov : a.fov, b.fov == null ? a.fov : b.fov, t);
        out.lens = lerpLens(a.lens, b.lens, t);
        out.lookAt = b.lookAt != null ? b.lookAt : a.lookAt;
        return out;
    }

    private static CameraLens lerpLens(CameraLens a, CameraLens b, float t) {
        if (a == null && b == null) return null;
        if (a == null) return b.copy();
        if (b == null) return a.copy();
        CameraLens lens = new CameraLens();
        lens.focalLength = lerp(a.focalLength, b.focalLength, t);
        lens.aperture = lerp(a.aperture, b.aperture, t);
        lens.iso = Math.round(lerp(a.iso, b.iso, t));
        lens.shutterSpeed = lerp(a.shutterSpeed, b.shutterSpeed, t);
        lens.exposureMode = b.exposureMode != null ? b.exposureMode : a.exposureMode;
        lens.focusMode = b.focusMode != null ? b.focusMode : a.focusMode;
        lens.focusDistance = lerp(a.focusDistance, b.focusDistance, t);
        return lens;
    }

    private static void applyLookAt(CameraState state, Vec3d target) {
        Vec3d delta = target.subtract(state.position);
        double horizontal = Math.sqrt(delta.x * delta.x + delta.z * delta.z);
        state.yaw = (float) (MathHelper.atan2(delta.z, delta.x) * (180.0d / Math.PI)) - 90.0f;
        state.pitch = (float) (-(MathHelper.atan2(delta.y, horizontal) * (180.0d / Math.PI)));
    }

    private static Vec3d lerp(Vec3d a, Vec3d b, float t) {
        if (a == null) a = Vec3d.ZERO;
        if (b == null) b = a;
        return new Vec3d(lerp(a.x, b.x, t), lerp(a.y, b.y, t), lerp(a.z, b.z, t));
    }

    private static float lerpAngle(float a, float b, float t) {
        float delta = MathHelper.wrapDegrees(b - a);
        return a + delta * t;
    }

    private static float lerp(double a, double b, float t) {
        return (float) (a + (b - a) * t);
    }

    private static float smooth(float t) {
        t = MathHelper.clamp(t, 0.0f, 1.0f);
        return t * t * (3.0f - 2.0f * t);
    }

    public static float focalLengthToFov(float focalLength) {
        float safeFocal = MathHelper.clamp(focalLength, 8.0f, 300.0f);
        double sensorHeight = 24.0d;
        double fov = 2.0d * Math.atan(sensorHeight / (2.0d * safeFocal)) * (180.0d / Math.PI);
        return MathHelper.clamp((float) fov, 15.0f, 110.0f);
    }
}
