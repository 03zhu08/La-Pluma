package cn.earthsky.dev.project.lapluma.common;

import cn.earthsky.dev.project.lapluma.LaPluma;
import cn.earthsky.dev.project.lapluma.client.camera.CameraRuntime;
import cn.earthsky.dev.project.lapluma.client.event.PlayJournalCommandEvent;
import cn.earthsky.dev.project.lapluma.client.gui.GuiDialog;
import cn.earthsky.dev.project.lapluma.client.gui.GuiVideoPlayer;
import cn.earthsky.dev.project.lapluma.client.gui.fx.FXFadeIn;
import cn.earthsky.dev.project.lapluma.client.gui.fx.FXFadeOut;
import cn.earthsky.dev.project.lapluma.client.gui.fx.FXShake;
import cn.earthsky.dev.project.lapluma.common.text.AVGCharacter;
import cn.earthsky.dev.project.lapluma.common.text.ConversationLoader;
import cn.earthsky.dev.project.lapluma.common.text.ConversationStructure;
import net.minecraft.client.Minecraft;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.SoundCategory;
import net.minecraft.util.SoundEvent;
import net.minecraft.util.text.TextComponentString;

import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.logging.Level;

public class Functions {

    private static final String[] VIDEO_EXTENSIONS = {".mp4", ".webm", ".avi", ".mkv", ".flv", ".mov", ".wmv", ".ogg"};

    /**
     * Resolves a local video file name to an absolute path FFmpegFrameGrabber can open.
     * Search order:
     *   1. .minecraft/lapluma/videos/<name> (with or without extension)
     *   2. Resource pack / mod assets: assets/lapluma/videos/<name>
     * For resource pack sources, the stream is extracted to a temp file.
     */
    public static String resolveLocalVideo(String name) {
        File mcDir = Minecraft.getMinecraft().gameDir;
        File videosDir = new File(mcDir, "lapluma" + File.separator + "videos");

        if (videosDir.isDirectory()) {
            File exact = new File(videosDir, name);
            if (exact.isFile()) return exact.getAbsolutePath();

            for (String ext : VIDEO_EXTENSIONS) {
                if (name.endsWith(ext)) continue;
                File withExt = new File(videosDir, name + ext);
                if (withExt.isFile()) return withExt.getAbsolutePath();
            }
        }

        for (String ext : VIDEO_EXTENSIONS) {
            String resName = name.endsWith(ext) ? name : name + ext;
            try {
                InputStream is = Minecraft.getMinecraft().getResourceManager()
                        .getResource(new ResourceLocation("lapluma", "videos/" + resName))
                        .getInputStream();
                File tmpDir = new File(mcDir, "lapluma" + File.separator + "cache");
                tmpDir.mkdirs();
                File tmpFile = new File(tmpDir, resName.replace('/', '_'));
                Files.copy(is, tmpFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
                is.close();
                LaPluma.getLogger().log(Level.INFO, "[Video] Extracted resource video to: " + tmpFile.getAbsolutePath());
                return tmpFile.getAbsolutePath();
            } catch (Throwable ignored) {}
        }

        LaPluma.getLogger().log(Level.WARNING, "[Video] Could not resolve local video: " + name);
        return null;
    }

    public final static void doFunction(Parsing parsing, GuiDialog screen){
        if(parsing == null || screen == null){
            return;
        }
        if(parsing.getFunctionName().equalsIgnoreCase("clean")){
            screen.clearCharacter();
        }
        else if(parsing.getFunctionName().equalsIgnoreCase("entity")){
            String id = Selector.searchNonNull(parsing.getArguments(),"entity","e","id","type","t","name","n");
            int pos = Parsers.parseInteger(Selector.searchNonNull(parsing.getArguments(),"pos","p","loc","location","position"), 50);
            float scale = Parsers.parseFloat(Selector.searchNonNull(parsing.getArguments(),"scale","s","size","sz"), 1.0f);
            boolean dimmed = Parsers.parseBoolean(Selector.searchNonNull(parsing.getArguments(),"dimmed","dim","dark","d"));
            int yOffset = Parsers.parseInteger(Selector.searchNonNull(parsing.getArguments(),"y","yoffset","yo","offset"), 0);
            boolean followMouse = Parsers.parseBoolean(Selector.searchNonNull(parsing.getArguments(),"follow","mouse","cursor","fm"));
            AVGCharacter character = new AVGCharacter(id, pos, dimmed, AVGCharacter.EntityMode.CREATE, scale, yOffset, followMouse);
            screen.addCharacter(character);
        }
        else if(parsing.getFunctionName().equalsIgnoreCase("worldEntity")){
            String player = Selector.searchNonNull(parsing.getArguments(),"player","pl","p");
            String displayName = Selector.searchNonNull(parsing.getArguments(),"displayname","display","dn","name","n");
            String uuid = Selector.searchNonNull(parsing.getArguments(),"uuid","u","uid");
            int pos = Parsers.parseInteger(Selector.searchNonNull(parsing.getArguments(),"pos","loc","location","position"), 50);
            float scale = Parsers.parseFloat(Selector.searchNonNull(parsing.getArguments(),"scale","s","size","sz"), 1.0f);
            boolean dimmed = Parsers.parseBoolean(Selector.searchNonNull(parsing.getArguments(),"dimmed","dim","dark","d"));
            int yOffset = Parsers.parseInteger(Selector.searchNonNull(parsing.getArguments(),"y","yoffset","yo","offset"), 0);
            boolean followMouse = Parsers.parseBoolean(Selector.searchNonNull(parsing.getArguments(),"follow","mouse","cursor","fm"));

            AVGCharacter.EntityMode mode;
            String identity;
            if(player != null){
                mode = AVGCharacter.EntityMode.PLAYER;
                identity = player;
            } else if(uuid != null){
                mode = AVGCharacter.EntityMode.UUID;
                identity = uuid;
            } else if(displayName != null){
                mode = AVGCharacter.EntityMode.DISPLAY_NAME;
                identity = displayName;
            } else {
                return;
            }
            AVGCharacter character = new AVGCharacter(identity, pos, dimmed, mode, scale, yOffset, followMouse);
            screen.addCharacter(character);
        }
        else if(parsing.getFunctionName().equalsIgnoreCase("show")){
            String id = Selector.searchNonNull(parsing.getArguments(),"avg","a","actor","act","id","path","val");
            boolean dimmed = Parsers.parseBoolean(Selector.searchNonNull(parsing.getArguments(),"dimmed","dim","dark","d"));
            int pos = Parsers.parseInteger(Selector.searchNonNull(parsing.getArguments(),"pos","p","loc","location","position"), 50);
            screen.addCharacter(new AVGCharacter(id,pos, dimmed));
        }else if(parsing.getFunctionName().equalsIgnoreCase("solo")){
            screen.clearCharacter();
            String id = Selector.searchNonNull(parsing.getArguments(),"avg","a","actor","act","id","path","val");
            int pos = Parsers.parseInteger(Selector.searchNonNull(parsing.getArguments(),"pos","p","loc","location","position"), 50);
            screen.addCharacter(new AVGCharacter(id,pos, false));
        }else if(parsing.getFunctionName().equalsIgnoreCase("bg")){
            String bg = Selector.searchNonNull(parsing.getArguments(),"bg","b","background","val","v");
            screen.setBackground(bg);
        }else if(parsing.getFunctionName().equalsIgnoreCase("resetBg")){
            screen.setBackground("bg");
        }else if(parsing.getFunctionName().equalsIgnoreCase("color")){
            int c = Parsers.parseInteger(Selector.searchNonNull(parsing.getArguments(),"color","colour","c","val","v"), 0xffff5733);
            screen.setSplitLineColor(c);
        }else if(parsing.getFunctionName().equalsIgnoreCase("center")){
            screen.setCenterText(true);
        }else if(parsing.getFunctionName().equalsIgnoreCase("stopCenter")){
            screen.setCenterText(false);
        }else if(parsing.getFunctionName().equalsIgnoreCase("fx_fadeIn")){
            screen.setFX(new FXFadeIn(screen));
        }else if(parsing.getFunctionName().equalsIgnoreCase("fx_fadeOut")){
            screen.setFX(new FXFadeOut(screen));
        }else if(parsing.getFunctionName().equalsIgnoreCase("fx_shakeShort")){
            FXShake shake = new FXShake(screen,2.5,4);
            shake.setAutoDismiss(true,22);
            screen.setFX(shake);
        }else if(parsing.getFunctionName().equalsIgnoreCase("fx_shakeFor")){
            int d = Parsers.parseInteger(Selector.searchNonNull(parsing.getArguments(),"t","time","d","duration"), 22);
            FXShake shake = new FXShake(screen,2.5,4);
            shake.setAutoDismiss(true,d);
            screen.setFX(shake);
        }else if(parsing.getFunctionName().equalsIgnoreCase("fx_shakeCustom")){
            int d = Parsers.parseInteger(Selector.searchNonNull(parsing.getArguments(),"t","time","d","duration"), 22);
            double a = Parsers.parseDouble(Selector.searchNonNull(parsing.getArguments(),"amp","amplitude","a"), 2.5);
            int t = Parsers.parseInteger(Selector.searchNonNull(parsing.getArguments(),"cycle","c","period","p"), 4);
            FXShake shake = new FXShake(screen,a,t);
            shake.setAutoDismiss(true,d);
            screen.setFX(shake);
        }else if(parsing.getFunctionName().equalsIgnoreCase("music")){
            String n = Selector.searchNonNull(parsing.getArguments(),"m","music","audio","a","sound","s","name","n","val","value","v");
            screen.setPlayingMusic(n);
        }else if(parsing.getFunctionName().equalsIgnoreCase("sound")){
            String n = Selector.searchNonNull(parsing.getArguments(),"s","n","sound","name");
            float volume = Parsers.parseFloat(Selector.searchNonNull(parsing.getArguments(),"volume","v"), 1.0f);
            float pitch = Parsers.parseFloat(Selector.searchNonNull(parsing.getArguments(),"pitch","p"), 1.0f);
            SoundEvent event = SoundEvent.REGISTRY.getObject(new ResourceLocation(n));
            if(event != null){
                Minecraft.getMinecraft().world.playSound(Minecraft.getMinecraft().player.getPosition(),event, SoundCategory.MASTER,volume, pitch, false);
            }
        }else if(parsing.getFunctionName().equalsIgnoreCase("isound")){

        }else if(parsing.getFunctionName().equalsIgnoreCase("continue")) {
            String journal = Selector.searchNonNull(parsing.getArguments(), "next", "n", "v", "value", "val", "journal", "j", "c", "d", "destination");
            if (Minecraft.getMinecraft().currentScreen != null && Minecraft.getMinecraft().currentScreen instanceof GuiDialog) {
                ConversationStructure str = JournalNamespace.get(journal);
                if(str != null) {
                    ((GuiDialog) Minecraft.getMinecraft().currentScreen).continueStructure(str);
                }
            }
        }else if(parsing.getFunctionName().equalsIgnoreCase("chat")){
            String msg = Selector.searchNonNull(parsing.getArguments(),"m","msg","chat","c","ctx","content","context","value","val","v");
            Minecraft.getMinecraft().player.sendChatMessage(msg);
        }else if(parsing.getFunctionName().equalsIgnoreCase("reverseSnapshot")){
            screen.reverseSnapshot();
        }else if(parsing.getFunctionName().equalsIgnoreCase("info")){
            String title = Selector.searchNonNull(parsing.getArguments(),"title","t");
            String abs = Selector.searchNonNull(parsing.getArguments(),"abstract","a","abs","description","desc","d");
            screen.getSkipMenu().setTitle(title);
            screen.getSkipMenu().setDescription(abs);
        }else if(parsing.getFunctionName().equalsIgnoreCase("video")){
            String url = Selector.searchNonNull(parsing.getArguments(),"url","u","src","source","link","l");
            String file = Selector.searchNonNull(parsing.getArguments(),"file","f","path","p","name","n");
            String skipArg = Selector.searchNonNull(parsing.getArguments(),"skip","skippable","canSkip","allowSkip","allow","esc");
            boolean allowSkip = skipArg == null || Parsers.parseBoolean(skipArg);
            String videoSource;
            if(url != null){
                videoSource = url;
            } else if(file != null){
                videoSource = resolveLocalVideo(file);
                if(videoSource == null) return;
            } else {
                return;
            }
            final String finalSource = videoSource;
            final boolean finalAllowSkip = allowSkip;
            screen.suspendForVideo();
            Minecraft.getMinecraft().addScheduledTask(() ->
                    GuiVideoPlayer.openVideo(finalSource, finalAllowSkip, screen));
        }else if(parsing.getFunctionName().equalsIgnoreCase("camera")){
            String cameraId = Selector.searchNonNull(parsing.getArguments(),"id","camera","cam","name","n","value","val","v");
            if(cameraId == null || cameraId.trim().isEmpty()) return;
            String blockingArg = Selector.searchNonNull(parsing.getArguments(),"blocking","block","wait","sync");
            boolean blocking = blockingArg == null || Parsers.parseBoolean(blockingArg);
            String skipArg = Selector.searchNonNull(parsing.getArguments(),"skip","skippable","canSkip","allowSkip","allow","esc");
            boolean allowSkip = skipArg == null || Parsers.parseBoolean(skipArg);
            if(blocking){
                screen.suspendForCamera();
                Minecraft.getMinecraft().addScheduledTask(() ->
                        CameraRuntime.play(cameraId, allowSkip, screen::resumeAfterCamera));
            }else{
                Minecraft.getMinecraft().addScheduledTask(() ->
                        CameraRuntime.play(cameraId, allowSkip, null, false));
            }
        }else if(parsing.getFunctionName().equalsIgnoreCase("waitCamera")){
            if(CameraRuntime.isPlaying()){
                screen.suspendForCamera();
                CameraRuntime.addCompletionCallback(screen::resumeAfterCamera);
            }
        }
    }
}
