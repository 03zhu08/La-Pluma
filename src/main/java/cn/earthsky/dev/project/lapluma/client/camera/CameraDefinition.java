package cn.earthsky.dev.project.lapluma.client.camera;

import java.util.ArrayList;
import java.util.List;

public class CameraDefinition {
    public String id;
    public Integer dimension;
    public String worldName;
    public int durationTicks = 20;
    public boolean allowSkip = true;
    public boolean hideHud = true;
    public boolean hideHand = true;
    public boolean lockInput = true;
    public boolean cinematicBars = true;
    public final List<CameraKeyframe> keyframes = new ArrayList<>();
    public final List<CameraKeyframe> fovTrack = new ArrayList<>();
    public final List<CameraKeyframe> lensTrack = new ArrayList<>();
    public final List<CameraEvent> events = new ArrayList<>();

    public int getDurationTicks() {
        int max = durationTicks;
        for (CameraKeyframe keyframe : keyframes) {
            max = Math.max(max, keyframe.time);
        }
        for (CameraKeyframe keyframe : fovTrack) {
            max = Math.max(max, keyframe.time);
        }
        for (CameraKeyframe keyframe : lensTrack) {
            max = Math.max(max, keyframe.time);
        }
        for (CameraEvent event : events) {
            max = Math.max(max, event.time);
        }
        return Math.max(max, 1);
    }

    public boolean hasWorldScope() {
        return dimension != null || (worldName != null && !worldName.trim().isEmpty());
    }

    public static class CameraEvent {
        public int time;
        public String event;
        public String type;
        public String value;
        public boolean fired;
    }
}
