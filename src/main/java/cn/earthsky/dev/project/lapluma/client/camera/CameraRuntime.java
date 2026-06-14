package cn.earthsky.dev.project.lapluma.client.camera;

import cn.earthsky.dev.project.lapluma.LaPluma;
import cn.earthsky.dev.project.lapluma.common.network.ProxyPacketHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.entity.Entity;
import net.minecraft.util.MovementInput;
import net.minecraftforge.client.event.EntityViewRenderEvent;
import net.minecraftforge.client.event.InputUpdateEvent;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.client.event.RenderSpecificHandEvent;
import org.lwjgl.input.Keyboard;

import java.util.logging.Level;
import java.util.ArrayList;
import java.util.List;

public class CameraRuntime {
    private static CameraSession activeSession;

    public static boolean isPlaying() {
        return activeSession != null;
    }

    public static String getActiveCameraId() {
        return activeSession == null ? null : activeSession.definition.id;
    }

    public static void play(String cameraId, boolean allowSkip, Runnable onComplete) {
        play(cameraId, allowSkip, onComplete, true);
    }

    public static void play(String cameraId, boolean allowSkip, Runnable onComplete, boolean takeOverScreen) {
        CameraDefinition definition = CameraResourceLoader.load(cameraId);
        if (definition == null) {
            if (onComplete != null) onComplete.run();
            sendResult(cameraId, "ERROR");
            return;
        }
        definition.allowSkip = allowSkip;
        play(definition, onComplete, takeOverScreen);
    }

    public static void play(CameraDefinition definition, Runnable onComplete) {
        play(definition, onComplete, true);
    }

    public static void play(CameraDefinition definition, Runnable onComplete, boolean takeOverScreen) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.world == null || mc.player == null || definition == null) {
            if (onComplete != null) onComplete.run();
            if (definition != null) sendResult(definition.id, "ERROR");
            return;
        }
        if (!isWorldMatched(mc, definition)) {
            LaPluma.getLogger().log(Level.WARNING, "[Camera] Refused camera for different or missing world scope: " + definition.id);
            if (onComplete != null) onComplete.run();
            sendResult(definition.id, "ERROR");
            return;
        }
        forceStop("SERVER_STOP", false);
        activeSession = new CameraSession(definition, onComplete, takeOverScreen);
        activeSession.start(mc);
    }

    private static boolean isWorldMatched(Minecraft mc, CameraDefinition definition) {
        if (!definition.hasWorldScope()) {
            return false;
        }
        if (definition.dimension != null && mc.world.provider.getDimension() != definition.dimension) {
            return false;
        }
        if (definition.worldName != null && !definition.worldName.trim().isEmpty()) {
            String currentWorld = mc.world.getWorldInfo() == null ? null : mc.world.getWorldInfo().getWorldName();
            return definition.worldName.equals(currentWorld);
        }
        return true;
    }

    public static void addCompletionCallback(Runnable callback) {
        if (callback == null) return;
        CameraSession session = activeSession;
        if (session == null) {
            callback.run();
        } else {
            session.completionCallbacks.add(callback);
        }
    }

    public static void forceStop() {
        forceStop("SERVER_STOP", true);
    }

    public static void forceStop(String result) {
        forceStop(result, true);
    }

    private static void forceStop(String result, boolean notify) {
        CameraSession session = activeSession;
        if (session == null) return;
        activeSession = null;
        session.stop(Minecraft.getMinecraft(), result, notify);
    }

    public static void tick() {
        CameraSession session = activeSession;
        if (session == null) return;
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.world == null || mc.player == null) {
            forceStop("ERROR");
            return;
        }
        if (session.definition.allowSkip && Keyboard.isKeyDown(Keyboard.KEY_ESCAPE)) {
            forceStop("SKIPPED");
            return;
        }
        session.tick(mc);
        if (session.isFinished()) {
            forceStop("FINISHED");
        }
    }

    public static void onCameraSetup(EntityViewRenderEvent.CameraSetup event) {
        CameraSession session = activeSession;
        if (session != null && session.cameraEntity != null && event.getEntity() == session.cameraEntity) {
            event.setRoll(session.cameraEntity.getRoll());
        }
    }

    public static void onFov(EntityViewRenderEvent.FOVModifier event) {
        CameraSession session = activeSession;
        if (session != null && session.cameraEntity != null && event.getEntity() == session.cameraEntity) {
            event.setFOV(session.cameraEntity.getFov());
        }
    }

    public static void onInputUpdate(InputUpdateEvent event) {
        CameraSession session = activeSession;
        if (session == null || !session.definition.lockInput) return;
        MovementInput input = event.getMovementInput();
        input.moveForward = 0.0f;
        input.moveStrafe = 0.0f;
        input.forwardKeyDown = false;
        input.backKeyDown = false;
        input.leftKeyDown = false;
        input.rightKeyDown = false;
        input.jump = false;
        input.sneak = false;
    }

    public static void onRenderOverlay(RenderGameOverlayEvent.Pre event) {
        CameraSession session = activeSession;
        if (session == null) return;
        if (session.definition.hideHud && event.getType() != RenderGameOverlayEvent.ElementType.CHAT) {
            event.setCanceled(true);
        }
        if (session.definition.cinematicBars && event.getType() == RenderGameOverlayEvent.ElementType.ALL) {
            int width = event.getResolution().getScaledWidth();
            int height = event.getResolution().getScaledHeight();
            int bar = Math.max(12, height / 12);
            Gui.drawRect(0, 0, width, bar, 0xFF000000);
            Gui.drawRect(0, height - bar, width, height, 0xFF000000);
        }
    }

    public static void onRenderHand(RenderSpecificHandEvent event) {
        CameraSession session = activeSession;
        if (session != null && session.definition.hideHand) {
            event.setCanceled(true);
        }
    }

    private static void sendResult(String cameraId, String result) {
        ProxyPacketHandler.sendPacket(42, 0, "{\"cameraId\":\"" + escape(cameraId) + "\",\"result\":\"" + result + "\"}");
    }

    private static String escape(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static class CameraSession {
        private final CameraDefinition definition;
        private final Runnable onComplete;
        private final boolean takeOverScreen;
        private final List<Runnable> completionCallbacks = new ArrayList<>();
        private CameraViewEntity cameraEntity;
        private Entity originalViewEntity;
        private boolean oldHideGui;
        private int oldThirdPersonView;
        private GuiScreen oldScreen;
        private int tick;

        private CameraSession(CameraDefinition definition, Runnable onComplete, boolean takeOverScreen) {
            this.definition = definition;
            this.onComplete = onComplete;
            this.takeOverScreen = takeOverScreen;
        }

        private void start(Minecraft mc) {
            oldScreen = mc.currentScreen;
            oldHideGui = mc.gameSettings.hideGUI;
            oldThirdPersonView = mc.gameSettings.thirdPersonView;
            originalViewEntity = mc.getRenderViewEntity();
            cameraEntity = new CameraViewEntity(mc.world);
            CameraState initial = CameraInterpolator.sample(definition, 0);
            cameraEntity.apply(initial);
            mc.setRenderViewEntity(cameraEntity);
            mc.gameSettings.thirdPersonView = 0;
            if (takeOverScreen) {
                mc.displayGuiScreen(null);
            }
            KeyBinding.unPressAllKeys();
            LaPluma.getLogger().log(Level.INFO, "[Camera] Started: " + definition.id);
        }

        private void tick(Minecraft mc) {
            KeyBinding.unPressAllKeys();
            CameraState state = CameraInterpolator.sample(definition, tick);
            cameraEntity.apply(state);
            dispatchEvents();
            tick++;
        }

        private void dispatchEvents() {
            for (CameraDefinition.CameraEvent event : definition.events) {
                if (!event.fired && tick >= event.time) {
                    event.fired = true;
                    String name = event.event != null ? event.event : event.value;
                    ProxyPacketHandler.sendPacket(44, tick, "{\"cameraId\":\"" + escape(definition.id)
                            + "\",\"event\":\"" + escape(name)
                            + "\",\"type\":\"" + escape(event.type) + "\"}");
                }
            }
        }

        private boolean isFinished() {
            return tick > definition.getDurationTicks();
        }

        private void stop(Minecraft mc, String result, boolean notify) {
            try {
                if (originalViewEntity != null && mc.world != null) {
                    mc.setRenderViewEntity(originalViewEntity);
                } else if (mc.player != null) {
                    mc.setRenderViewEntity(mc.player);
                }
                mc.gameSettings.hideGUI = oldHideGui;
                mc.gameSettings.thirdPersonView = oldThirdPersonView;
                if (takeOverScreen && oldScreen != null && mc.world != null && mc.player != null) {
                    mc.displayGuiScreen(oldScreen);
                }
            } finally {
                KeyBinding.unPressAllKeys();
                if (notify) sendResult(definition.id, result);
                if (onComplete != null) onComplete.run();
                for (Runnable callback : completionCallbacks) {
                    callback.run();
                }
                LaPluma.getLogger().log(Level.INFO, "[Camera] Stopped: " + definition.id + " result=" + result);
            }
        }
    }
}
