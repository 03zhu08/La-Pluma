package cn.earthsky.dev.project.lapluma.common.commands;

import cn.earthsky.dev.project.lapluma.client.gui.GuiVideoPlayer;
import cn.earthsky.dev.project.lapluma.common.Functions;
import net.minecraft.client.Minecraft;
import net.minecraft.command.CommandBase;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.text.TextComponentString;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class PlayVideoCommand extends CommandBase {

    private static final ScheduledExecutorService GUI_SCHEDULER = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "LaPluma-GuiScheduler");
        t.setDaemon(true);
        return t;
    });

    @Override
    public String getName() {
        return "playVideo";
    }

    @Override
    public String getUsage(ICommandSender sender) {
        return "/playVideo <url|name|path> [allowSkip]";
    }

    @Override
    public void execute(MinecraftServer server, ICommandSender sender, String[] args) throws CommandException {
        if (args.length >= 1) {
            boolean allowSkip = true;
            int sourceArgCount = args.length;
            String lastArg = args[args.length - 1];
            if (args.length >= 2 && isBooleanArg(lastArg)) {
                allowSkip = parseBooleanArg(lastArg);
                sourceArgCount = args.length - 1;
            }

            String source = String.join(" ", java.util.Arrays.copyOfRange(args, 0, sourceArgCount)).trim();
            String resolved = resolveSource(source);
            if (resolved == null) {
                sender.sendMessage(new TextComponentString("\u00A7c\u627e\u4e0d\u5230\u89c6\u9891: " + source));
                return;
            }
            sender.sendMessage(new TextComponentString("\u00A79\u6b63\u5728\u64ad\u653e\u89c6\u9891: " + source + (allowSkip ? " \u00A77(\u53ef\u8df3\u8fc7)" : " \u00A77(\u4e0d\u53ef\u8df3\u8fc7)")));
            
            scheduleDisplay(resolved, allowSkip);
        } else {
            sender.sendMessage(new TextComponentString("\u00A7e\u6b63\u786e\u683c\u5f0f: /playVideo <url|name|path> [allowSkip]"));
        }
    }

    private static void scheduleDisplay(String source, boolean allowSkip) {
        // Delay slightly so chat screen close (after pressing Enter) won't immediately close the video GUI.
        GUI_SCHEDULER.schedule(() ->
                Minecraft.getMinecraft().addScheduledTask(() ->
                        GuiVideoPlayer.openVideo(source, allowSkip, null)),
                150, TimeUnit.MILLISECONDS);
    }

    private static boolean isBooleanArg(String arg) {
        String value = arg.toLowerCase();
        return value.equals("true")
                || value.equals("false")
                || value.equals("yes")
                || value.equals("no")
                || value.equals("on")
                || value.equals("off")
                || value.equals("allow")
                || value.equals("disallow")
                || value.equals("allowed")
                || value.equals("forbid")
                || value.equals("forbidden");
    }

    private static boolean parseBooleanArg(String arg) {
        String value = arg.toLowerCase();
        return value.equals("true")
                || value.equals("yes")
                || value.equals("on")
                || value.equals("allow")
                || value.equals("allowed");
    }

    private static String resolveSource(String source) {
        source = source == null ? null : source.trim();
        if (source == null || source.isEmpty()) {
            return null;
        }
        if (source.startsWith("http://") || source.startsWith("https://") || source.startsWith("rtmp://")) {
            return source;
        }

        java.io.File asFile = new java.io.File(source);
        if (asFile.isAbsolute() && asFile.isFile()) {
            return asFile.getAbsolutePath();
        }

        String resolved = Functions.resolveLocalVideo(source);
        if (resolved != null) return resolved;

        return source;
    }
}
