package cn.earthsky.dev.project.lapluma.client.camera;

public class CameraLens {
    public float focalLength = 35.0f;
    public float aperture = 2.8f;
    public int iso = 100;
    public float shutterSpeed = 0.0167f;
    public String exposureMode = "MANUAL";
    public String focusMode = "MANUAL";
    public float focusDistance = 8.0f;

    public CameraLens copy() {
        CameraLens lens = new CameraLens();
        lens.focalLength = focalLength;
        lens.aperture = aperture;
        lens.iso = iso;
        lens.shutterSpeed = shutterSpeed;
        lens.exposureMode = exposureMode;
        lens.focusMode = focusMode;
        lens.focusDistance = focusDistance;
        return lens;
    }
}
