package cn.earthsky.dev.project.lapluma.common.network;

import cn.earthsky.dev.project.lapluma.client.gui.chat.GuiChatScreen;
import cn.earthsky.dev.project.lapluma.client.gui.chat.data.ChatDataManager;
import cn.earthsky.dev.project.lapluma.LaPluma;
import cn.earthsky.dev.project.lapluma.client.gui.GuiDialog;
import cn.earthsky.dev.project.lapluma.client.gui.GuiVideoPlayer;
import cn.earthsky.dev.project.lapluma.common.Functions;
import cn.earthsky.dev.project.lapluma.common.JournalNamespace;
import cn.earthsky.dev.project.lapluma.common.Parsing;
import com.google.common.base.Charsets;
import cn.earthsky.dev.project.lapluma.common.text.ConversationLoader;
import cn.earthsky.dev.project.lapluma.common.text.ConversationStructure;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.network.PacketBuffer;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.EntityJoinWorldEvent;
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.network.FMLEventChannel;
import net.minecraftforge.fml.common.network.FMLNetworkEvent;
import net.minecraftforge.fml.common.network.NetworkRegistry;
import net.minecraftforge.fml.common.network.internal.FMLProxyPacket;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

public class ProxyPacketHandler {

    static FMLEventChannel channel;
    public static final String MSG_CHANNEL = "SkyHUDMessage";

    private static final Map<String, StringBuilder[]> chunkBuffers = new ConcurrentHashMap<>();
    private static volatile byte[] signingKey = null;

    public static boolean chatRequestPending = false;
    public static long chatRequestTime = 0;

    private static String hmacSha256(byte[] key, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            byte[] hash = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) {
                hex.append(String.format("%02x", b & 0xff));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            return "";
        }
    }

    private static byte[] hexToBytes(String hex) {
        int len = hex.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte) ((Character.digit(hex.charAt(i), 16) << 4)
                    + Character.digit(hex.charAt(i + 1), 16));
        }
        return data;
    }

    public void init(){
        MinecraftForge.EVENT_BUS.register(this);
        FMLCommonHandler.instance().bus().register(this);
        ProxyPacketHandler.channel = NetworkRegistry.INSTANCE.newEventDrivenChannel(MSG_CHANNEL);
        channel.register(this);
    }

    private String lastWorld;

    @SubscribeEvent
    public void onQuitServer(FMLNetworkEvent.ClientDisconnectionFromServerEvent evt){
        lastWorld = null;
        chunkBuffers.clear();
        signingKey = null;
        chatRequestPending = false;
        JournalNamespace.clearRemote();
        ChatDataManager.reset();
    }

    @SubscribeEvent
    public void onJoinServer(EntityJoinWorldEvent evt){
        if(Objects.equals(lastWorld, evt.getWorld().getWorldInfo().getWorldName())){
            return;
        }
        if(evt.getEntity() instanceof EntityPlayerSP && Minecraft.getMinecraft().player == evt.getEntity()) {
            lastWorld = evt.getWorld().getWorldInfo().getWorldName();
            Minecraft.getMinecraft().addScheduledTask(() -> {
                if (Minecraft.getMinecraft().isSingleplayer()) {
                    ChatDataManager.loadTestData();
                } else {
                    ChatDataManager.reset();
                    ByteBuf pool = Unpooled.buffer();
                    pool.writeInt(-1);
                    pool.writeInt(0);
                    pool.writeBytes((LaPluma.MD5HASH).getBytes(Charsets.UTF_8));
                    FMLProxyPacket packet = new FMLProxyPacket(new PacketBuffer(pool), MSG_CHANNEL);
                    channel.sendToServer(packet);
                }
            });
        }
    }

    public static void sendPacket(int act, int data, String ctx){
        ByteBuf pool = Unpooled.buffer();
        pool.writeInt(act);
        pool.writeInt(data);
        pool.writeBytes(ctx.getBytes(Charsets.UTF_8));
        FMLProxyPacket packet = new FMLProxyPacket(new PacketBuffer(pool), MSG_CHANNEL);
        channel.sendToServer(packet);
    }

    @SubscribeEvent
    public void onClientPacket(FMLNetworkEvent.ClientCustomPacketEvent evt) {
        FMLProxyPacket packet = evt.getPacket();
        if(packet.channel().equals(MSG_CHANNEL)){
            ByteBuf byteBuf = evt.getPacket().payload();
            int a = byteBuf.readInt();
            int b = byteBuf.readInt();
            final String c = byteBuf.toString(Charsets.UTF_8).replaceAll("\0","");

            if(a == 0) {
                if(c.length() > 0){
                    Minecraft.getMinecraft().addScheduledTask(() -> {
                        ConversationStructure str = JournalNamespace.get(c);
                        if(str != null){
                            Minecraft.getMinecraft().displayGuiScreen(new GuiDialog(str));
                            ProxyPacketHandler.sendPacket(1,0, str.getName());
                        }
                    });
                }
            } else if(a == 2) {
                GuiScreen screen = Minecraft.getMinecraft().currentScreen;
                if(screen instanceof GuiDialog){
                    try{
                        Functions.doFunction(new Parsing(c), (GuiDialog) screen);
                    }catch (Throwable throwable){
                        LaPluma.getLogger().log(Level.WARNING, "cannot parse function '" + c + " from server", throwable);
                    }
                }
            } else if(a == 4) {
                if(c.length() > 0) {
                    Minecraft.getMinecraft().addScheduledTask(() ->
                            GuiVideoPlayer.openVideo(c));
                }
            } else if(a == 9) {
                System.out.println("Received Handled Message: " + c);
                PlaceholderConnect.handleRequest(b, c);
            } else if(a == 10) {
                handleJournalTransfer(b, c);
            } else if(a == 12) {
                signingKey = hexToBytes(c);
                LaPluma.getLogger().log(Level.INFO, "[Security] Received signing key from server");
            } else if(a == 20) {
                chatRequestPending = false;
                Minecraft.getMinecraft().addScheduledTask(() ->
                        Minecraft.getMinecraft().displayGuiScreen(new GuiChatScreen(c.isEmpty() ? null : c)));
            } else if(a == 21) {
                Minecraft.getMinecraft().addScheduledTask(() -> ChatDataManager.parseContactsJson(c));
            } else if(a == 22) {
                Minecraft.getMinecraft().addScheduledTask(() -> ChatDataManager.parseMessagesJson(c));
            } else if(a == 24) {
                Minecraft.getMinecraft().addScheduledTask(() -> ChatDataManager.parseNewMessageJson(c));
            } else if(a == 26) {
                Minecraft.getMinecraft().addScheduledTask(() -> ChatDataManager.setTyping(c, b == 1));
            } else if(a == 27) {
                Minecraft.getMinecraft().addScheduledTask(() -> ChatDataManager.parseUpdateContactJson(c));
            }
        }
    }

    private void handleJournalTransfer(int data, String ctx) {
        if (signingKey == null) {
            LaPluma.getLogger().log(Level.WARNING, "[JournalTransfer] Rejected: no signing key received yet");
            return;
        }

        if (data == 0) {
            // Single packet: journalName|signature|content
            int firstSep = ctx.indexOf('|');
            if (firstSep < 0) return;
            int secondSep = ctx.indexOf('|', firstSep + 1);
            if (secondSep < 0) return;

            String journalName = ctx.substring(0, firstSep);
            String receivedSig = ctx.substring(firstSep + 1, secondSep);
            String content = ctx.substring(secondSep + 1);

            String expectedSig = hmacSha256(signingKey, journalName + content);
            if (!expectedSig.equals(receivedSig)) {
                LaPluma.getLogger().log(Level.WARNING, "[JournalTransfer] HMAC mismatch for journal: " + journalName + " — rejected");
                return;
            }

            ConversationStructure structure = ConversationLoader.loadStructureFromString(journalName, content);
            if (structure != null) {
                JournalNamespace.put(journalName, structure);
                LaPluma.getLogger().log(Level.INFO, "[JournalTransfer] Verified & received journal: " + journalName);
            }
        } else if (data == -1) {
            // Final verification packet: journalName|fullSignature|totalChunks
            int firstSep = ctx.indexOf('|');
            if (firstSep < 0) return;
            int secondSep = ctx.indexOf('|', firstSep + 1);
            if (secondSep < 0) return;

            String journalName = ctx.substring(0, firstSep);
            String fullSignature = ctx.substring(firstSep + 1, secondSep);
            int totalChunks;
            try {
                totalChunks = Integer.parseInt(ctx.substring(secondSep + 1));
            } catch (NumberFormatException e) {
                return;
            }

            StringBuilder[] chunks = chunkBuffers.remove(journalName);
            if (chunks == null || chunks.length != totalChunks) {
                LaPluma.getLogger().log(Level.WARNING, "[JournalTransfer] Missing chunks for: " + journalName);
                return;
            }
            for (StringBuilder chunk : chunks) {
                if (chunk == null) {
                    LaPluma.getLogger().log(Level.WARNING, "[JournalTransfer] Incomplete chunks for: " + journalName);
                    return;
                }
            }

            StringBuilder full = new StringBuilder();
            for (StringBuilder chunk : chunks) {
                full.append(chunk);
            }
            String fullContent = full.toString();

            String expectedSig = hmacSha256(signingKey, journalName + fullContent);
            if (!expectedSig.equals(fullSignature)) {
                LaPluma.getLogger().log(Level.WARNING, "[JournalTransfer] HMAC mismatch for assembled journal: " + journalName + " — rejected");
                return;
            }

            ConversationStructure structure = ConversationLoader.loadStructureFromString(journalName, fullContent);
            if (structure != null) {
                JournalNamespace.put(journalName, structure);
                LaPluma.getLogger().log(Level.INFO, "[JournalTransfer] Verified & assembled journal: " + journalName + " (" + totalChunks + " chunks)");
            }
            sendPacket(11, 0, journalName);
        } else {
            // Chunk packet: journalName|chunkIndex|chunkSig|chunkContent
            int totalChunks = data;
            int firstSep = ctx.indexOf('|');
            if (firstSep < 0) return;
            int secondSep = ctx.indexOf('|', firstSep + 1);
            if (secondSep < 0) return;
            int thirdSep = ctx.indexOf('|', secondSep + 1);
            if (thirdSep < 0) return;

            String journalName = ctx.substring(0, firstSep);
            int chunkIndex;
            try {
                chunkIndex = Integer.parseInt(ctx.substring(firstSep + 1, secondSep));
            } catch (NumberFormatException e) {
                return;
            }
            String chunkSig = ctx.substring(secondSep + 1, thirdSep);
            String chunkContent = ctx.substring(thirdSep + 1);

            String expectedChunkSig = hmacSha256(signingKey, journalName + chunkIndex + chunkContent);
            if (!expectedChunkSig.equals(chunkSig)) {
                LaPluma.getLogger().log(Level.WARNING, "[JournalTransfer] HMAC mismatch for chunk " + chunkIndex + " of: " + journalName + " — rejected");
                return;
            }

            StringBuilder[] chunks = chunkBuffers.computeIfAbsent(journalName, k -> new StringBuilder[totalChunks]);
            if (chunkIndex >= 0 && chunkIndex < chunks.length) {
                chunks[chunkIndex] = new StringBuilder(chunkContent);
            }
        }
    }

    /*
    0 int - 命令类型
            0 - 打开对话
            1 - hash合法验证
            2 - 运行function
            4 - 播放视频 (服务端→客户端, ctx=URL)
            5 - 视频播放结束 (客户端→服务端)
            9 - papi
            10 - 对话文件传输 (服务端→客户端, HMAC签名)
                 data=0: 单包 ctx=journalName|signature|content
                 data>0: 分片 ctx=journalName|chunkIndex|chunkSig|chunkContent
                 data=-1: 完整性验证包 ctx=journalName|fullSignature|totalChunks
            11 - 对话文件传输完成确认 (客户端→服务端)
            12 - 签名密钥下发 (服务端→客户端, ctx=hex密钥)
            20 - 打开聊天界面 (服务端→客户端, ctx=contactId可选)
            21 - 推送联系人列表 (服务端→客户端, data: 0=全量/1=增量, ctx=JSON)
            22 - 推送历史消息 (服务端→客户端, data=pageIndex, ctx=JSON)
            24 - 推送新消息 (服务端→客户端, ctx=JSON)
            25 - 玩家选择回复 (客户端→服务端, data=optionIndex, ctx=JSON)
            26 - 输入中指示器 (服务端→客户端, data: 0=停止/1=开始, ctx=contactId)
            27 - 更新单个联系人 (服务端→客户端, ctx=JSON)
            28 - 对话已读完毕 (客户端→服务端, ctx=contactId)
            23 - KeepAlive
    1 int - 附加内容
               0 = 0 空
               0 = 1 是否合法 true 1 / false 0
               0 = 9 PAPI parse编码 index
               0 = 10 分片总数 (0=单包, -1=验证包)
               0 = 23 Cursor Index
    2 string - 对话名 / URL (act=4) / 签名journal数据 (act=10) / hex密钥 (act=12)
     */
}
