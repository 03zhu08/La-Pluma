package cn.earthsky.dev.project.lapluma.client.camera;

import cn.earthsky.dev.project.lapluma.LaPluma;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.IResource;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.Vec3d;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.logging.Level;

public class CameraResourceLoader {

    public static CameraDefinition load(String cameraId) {
        try {
            IResource resource = Minecraft.getMinecraft().getResourceManager()
                    .getResource(new ResourceLocation("lapluma", "cameras/" + cameraId + ".cam.json"));
            JsonObject root = new JsonParser().parse(new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8)).getAsJsonObject();
            CameraDefinition definition = parse(cameraId, root);
            LaPluma.getLogger().log(Level.INFO, "[Camera] Loaded camera: " + cameraId);
            return definition;
        } catch (Throwable throwable) {
            LaPluma.getLogger().log(Level.WARNING, "[Camera] Failed to load camera: " + cameraId, throwable);
            return null;
        }
    }

    public static CameraDefinition parse(String fallbackId, JsonObject root) {
        CameraDefinition definition = new CameraDefinition();
        definition.id = string(root, "id", fallbackId);
        readWorldScope(root, definition);
        definition.durationTicks = ticks(root);
        definition.allowSkip = bool(root, "allowSkip", definition.allowSkip);
        definition.hideHud = bool(root, "hideHud", bool(root, "hideHUD", definition.hideHud));
        definition.hideHand = bool(root, "hideHand", definition.hideHand);
        definition.lockInput = bool(root, "lockInput", definition.lockInput);
        definition.cinematicBars = bool(root, "cinematicBars", definition.cinematicBars);

        CameraLens defaultLens = root.has("lens") && root.get("lens").isJsonObject()
                ? parseLens(root.getAsJsonObject("lens"), new CameraLens())
                : new CameraLens();

        readKeyframes(root, "keyframes", definition.keyframes, defaultLens);
        readKeyframes(root, "transform", definition.keyframes, defaultLens);
        readKeyframes(root, "transformTrack", definition.keyframes, defaultLens);
        readKeyframes(root, "fov", definition.fovTrack, defaultLens);
        readKeyframes(root, "fovTrack", definition.fovTrack, defaultLens);
        readKeyframes(root, "lensTrack", definition.lensTrack, defaultLens);
        readKeyframes(root, "lens", definition.lensTrack, defaultLens);
        readEvents(root, definition);

        if (definition.keyframes.isEmpty()) {
            CameraKeyframe keyframe = new CameraKeyframe();
            keyframe.time = 0;
            keyframe.lens = defaultLens.copy();
            definition.keyframes.add(keyframe);
        }
        sort(definition.keyframes);
        sort(definition.fovTrack);
        sort(definition.lensTrack);
        Collections.sort(definition.events, Comparator.comparingInt(event -> event.time));
        return definition;
    }

    private static void readWorldScope(JsonObject root, CameraDefinition definition) {
        if (root.has("world") && root.get("world").isJsonObject()) {
            JsonObject world = root.getAsJsonObject("world");
            if (world.has("dimension")) definition.dimension = world.get("dimension").getAsInt();
            definition.worldName = string(world, "name", string(world, "worldName", definition.worldName));
        }
        if (root.has("dimension")) definition.dimension = root.get("dimension").getAsInt();
        definition.worldName = string(root, "worldName", definition.worldName);
    }

    private static int ticks(JsonObject root) {
        if (root.has("durationTicks")) return root.get("durationTicks").getAsInt();
        if (root.has("duration")) {
            double duration = root.get("duration").getAsDouble();
            return duration <= 1000 ? (int) Math.round(duration * 20.0d) : (int) Math.round(duration / 50.0d);
        }
        return 20;
    }

    private static void readKeyframes(JsonObject root, String key, List<CameraKeyframe> target, CameraLens defaultLens) {
        if (!root.has(key)) return;
        JsonElement element = root.get(key);
        if (element.isJsonObject()) {
            target.add(parseKeyframe(element.getAsJsonObject(), defaultLens));
        } else if (element.isJsonArray()) {
            JsonArray array = element.getAsJsonArray();
            for (JsonElement entry : array) {
                if (entry.isJsonObject()) {
                    target.add(parseKeyframe(entry.getAsJsonObject(), defaultLens));
                }
            }
        }
    }

    private static CameraKeyframe parseKeyframe(JsonObject obj, CameraLens defaultLens) {
        CameraKeyframe keyframe = new CameraKeyframe();
        keyframe.time = keyframeTime(obj);
        keyframe.position = vec(obj, "position", vec(obj, "pos", Vec3d.ZERO));
        if (obj.has("rotation")) {
            JsonArray rotation = obj.getAsJsonArray("rotation");
            keyframe.pitch = get(rotation, 0, 0.0f);
            keyframe.yaw = get(rotation, 1, 0.0f);
            keyframe.roll = get(rotation, 2, 0.0f);
        } else {
            keyframe.pitch = number(obj, "pitch", 0.0f);
            keyframe.yaw = number(obj, "yaw", 0.0f);
            keyframe.roll = number(obj, "roll", 0.0f);
        }
        if (obj.has("fov")) keyframe.fov = obj.get("fov").getAsFloat();
        keyframe.lens = obj.has("lens") && obj.get("lens").isJsonObject()
                ? parseLens(obj.getAsJsonObject("lens"), defaultLens)
                : parseLens(obj, defaultLens);
        keyframe.lookAt = vec(obj, "lookAt", null);
        return keyframe;
    }

    private static CameraLens parseLens(JsonObject obj, CameraLens fallback) {
        CameraLens lens = fallback == null ? new CameraLens() : fallback.copy();
        lens.focalLength = number(obj, "focalLength", lens.focalLength);
        lens.aperture = number(obj, "aperture", lens.aperture);
        lens.iso = (int) number(obj, "iso", lens.iso);
        lens.shutterSpeed = number(obj, "shutterSpeed", lens.shutterSpeed);
        lens.exposureMode = string(obj, "exposureMode", lens.exposureMode);
        lens.focusMode = string(obj, "focusMode", lens.focusMode);
        lens.focusDistance = number(obj, "focusDistance", lens.focusDistance);
        return lens;
    }

    private static void readEvents(JsonObject root, CameraDefinition definition) {
        if (!root.has("events") || !root.get("events").isJsonArray()) return;
        for (JsonElement entry : root.getAsJsonArray("events")) {
            if (!entry.isJsonObject()) continue;
            JsonObject obj = entry.getAsJsonObject();
            CameraDefinition.CameraEvent event = new CameraDefinition.CameraEvent();
            event.time = keyframeTime(obj);
            event.event = string(obj, "event", null);
            event.type = string(obj, "type", null);
            event.value = string(obj, "value", event.event);
            definition.events.add(event);
        }
    }

    private static Vec3d vec(JsonObject obj, String key, Vec3d fallback) {
        if (!obj.has(key) || !obj.get(key).isJsonArray()) return fallback;
        JsonArray array = obj.getAsJsonArray(key);
        return new Vec3d(get(array, 0, 0.0f), get(array, 1, 0.0f), get(array, 2, 0.0f));
    }

    private static float get(JsonArray array, int index, float fallback) {
        return array.size() > index ? array.get(index).getAsFloat() : fallback;
    }

    private static String string(JsonObject obj, String key, String fallback) {
        return obj.has(key) && !obj.get(key).isJsonNull() ? obj.get(key).getAsString() : fallback;
    }

    private static boolean bool(JsonObject obj, String key, boolean fallback) {
        return obj.has(key) && !obj.get(key).isJsonNull() ? obj.get(key).getAsBoolean() : fallback;
    }

    private static float number(JsonObject obj, String key, float fallback) {
        return obj.has(key) && !obj.get(key).isJsonNull() ? obj.get(key).getAsFloat() : fallback;
    }

    private static int timeToTicks(double time) {
        return time <= 1000.0d ? (int) Math.round(time * 20.0d) : (int) Math.round(time / 50.0d);
    }

    private static int keyframeTime(JsonObject obj) {
        if (obj.has("timeTicks")) return obj.get("timeTicks").getAsInt();
        if (obj.has("ticks")) return obj.get("ticks").getAsInt();
        if (obj.has("tick")) return obj.get("tick").getAsInt();
        return obj.has("time") ? timeToTicks(obj.get("time").getAsDouble()) : 0;
    }

    private static void sort(List<CameraKeyframe> keyframes) {
        Collections.sort(keyframes, Comparator.comparingInt(keyframe -> keyframe.time));
    }
}
