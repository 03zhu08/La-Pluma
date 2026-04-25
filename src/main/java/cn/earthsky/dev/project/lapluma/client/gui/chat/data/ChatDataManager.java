package cn.earthsky.dev.project.lapluma.client.gui.chat.data;

import cn.earthsky.dev.project.lapluma.LaPluma;
import cn.earthsky.dev.project.lapluma.common.network.ProxyPacketHandler;
import com.google.gson.*;
import com.google.gson.reflect.TypeToken;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.IResource;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.SoundCategory;

import java.io.InputStreamReader;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

public class ChatDataManager {

    private static final Gson GSON = new GsonBuilder()
            .setFieldNamingPolicy(FieldNamingPolicy.IDENTITY)
            .create();

    private static final Map<String, ChatContact> contacts = new ConcurrentHashMap<>();
    private static final Map<String, List<ChatMessage>> messages = new ConcurrentHashMap<>();
    private static final Set<String> typingContacts = Collections.newSetFromMap(new ConcurrentHashMap<>());

    private static final String PLAYER_ID = "$player";

    private static final Map<String, Map<String, Conversation>> conversations = new ConcurrentHashMap<>();
    private static final Map<String, String> activeConversationIds = new ConcurrentHashMap<>();

    public static class Conversation {
        public String conversationId;
        public String contactId;
        public String title;
        public String status;
        public List<ChatMessage> allMessages;
        public int sequenceStart;
        public int revealedInSequence;
        public int sequenceLength;
        public int typingDelayTicks;
        public boolean isTyping;
        public boolean finished;
        public boolean returnToMain;
        public int mainContinuationIndex;
        public Map<String, Integer> branchIndexMap;
        public Set<Integer> branchStarts;
        public final Set<Integer> revealedIndices = new LinkedHashSet<>();

        public Conversation(String conversationId, String contactId, String title, String status,
                            List<ChatMessage> allMessages, Map<String, Integer> branchIndexMap,
                            Set<Integer> branchStarts, int mainContinuationIndex) {
            this.conversationId = conversationId;
            this.contactId = contactId;
            this.title = title != null ? title : conversationId;
            this.status = status;
            this.allMessages = allMessages;
            this.sequenceStart = 0;
            this.revealedInSequence = 0;
            this.sequenceLength = computeSequenceLength(0, allMessages, branchStarts, mainContinuationIndex);
            this.typingDelayTicks = 0;
            this.isTyping = false;
            this.finished = "completed".equals(status);
            this.returnToMain = false;
            this.mainContinuationIndex = mainContinuationIndex;
            this.branchIndexMap = branchIndexMap;
            this.branchStarts = branchStarts;
            if ("completed".equals(status)) {
                for (int i = 0; i < allMessages.size(); i++) {
                    revealedIndices.add(i);
                }
            }
        }

        public List<ChatMessage> getVisibleMessages() {
            List<Integer> sorted = new ArrayList<>(revealedIndices);
            Collections.sort(sorted);
            List<ChatMessage> result = new ArrayList<>();
            for (int idx : sorted) {
                if (idx >= 0 && idx < allMessages.size()) {
                    result.add(allMessages.get(idx));
                }
            }
            return result;
        }

        public boolean hasNextMessage() {
            if (revealedInSequence >= sequenceLength) return false;
            int nextIdx = sequenceStart + revealedInSequence;
            if (nextIdx >= allMessages.size()) return false;
            return true;
        }

        public ChatMessage peekNext() {
            if (!hasNextMessage()) return null;
            return allMessages.get(sequenceStart + revealedInSequence);
        }

        public void revealNext() {
            int idx = sequenceStart + revealedInSequence;
            if (idx >= 0 && idx < allMessages.size()) {
                revealedIndices.add(idx);
                allMessages.get(idx).setRevealTime(System.currentTimeMillis());
            }
            revealedInSequence++;
        }
    }

    private static int computeSequenceLength(int fromIndex, List<ChatMessage> allMessages,
                                              Set<Integer> branchStarts, int endCap) {
        int end = allMessages.size();
        if (endCap > 0 && fromIndex < endCap) {
            end = endCap;
        }
        if (branchStarts != null && !branchStarts.isEmpty()) {
            for (int bs : branchStarts) {
                if (bs > fromIndex && bs < end) {
                    end = bs;
                }
            }
        }
        return end - fromIndex;
    }

    public static Map<String, ChatContact> getContacts() {
        return contacts;
    }

    public static List<ChatContact> getContactsSorted() {
        List<ChatContact> list = new ArrayList<>(contacts.values());
        list.sort((a, b) -> Long.compare(b.getLastTimestamp(), a.getLastTimestamp()));
        return list;
    }

    public static List<ChatMessage> getMessages(String contactId) {
        Conversation conv = getActiveConversation(contactId);
        if (conv != null) return conv.getVisibleMessages();
        return messages.getOrDefault(contactId, Collections.emptyList());
    }

    public static Conversation getConversation(String contactId) {
        return getActiveConversation(contactId);
    }

    public static Conversation getActiveConversation(String contactId) {
        Map<String, Conversation> contactConvs = conversations.get(contactId);
        if (contactConvs == null || contactConvs.isEmpty()) return null;
        String activeId = activeConversationIds.get(contactId);
        if (activeId != null && contactConvs.containsKey(activeId)) {
            return contactConvs.get(activeId);
        }
        Conversation first = contactConvs.values().iterator().next();
        activeConversationIds.put(contactId, first.conversationId);
        return first;
    }

    public static List<Conversation> getConversationsForContact(String contactId) {
        Map<String, Conversation> contactConvs = conversations.get(contactId);
        if (contactConvs == null) return Collections.emptyList();
        return new ArrayList<>(contactConvs.values());
    }

    public static void switchConversation(String contactId, String conversationId) {
        Map<String, Conversation> contactConvs = conversations.get(contactId);
        if (contactConvs != null && contactConvs.containsKey(conversationId)) {
            activeConversationIds.put(contactId, conversationId);
        }
    }

    public static boolean isTyping(String contactId) {
        Conversation conv = getActiveConversation(contactId);
        if (conv != null && conv.isTyping) return true;
        return typingContacts.contains(contactId);
    }

    public static void setTyping(String contactId, boolean typing) {
        if (typing) typingContacts.add(contactId);
        else typingContacts.remove(contactId);
    }

    public static void setContacts(List<ChatContact> list) {
        contacts.clear();
        for (ChatContact c : list) contacts.put(c.getId(), c);
    }

    public static void updateContact(ChatContact contact) {
        contacts.put(contact.getId(), contact);
    }

    public static void setMessages(String contactId, List<ChatMessage> msgs) {
        messages.put(contactId, new ArrayList<>(msgs));
    }

    public static void addMessage(String contactId, ChatMessage msg) {
        Conversation conv = getActiveConversation(contactId);
        if (conv != null && PLAYER_ID.equals(msg.getSenderId())) {
            int insertPos = conv.sequenceStart + conv.revealedInSequence;
            conv.allMessages.add(insertPos, msg);
            conv.revealedIndices.add(insertPos);
            conv.revealedInSequence++;
        } else {
            messages.computeIfAbsent(contactId, k -> new ArrayList<>()).add(msg);
        }
        ChatContact contact = contacts.get(contactId);
        if (contact != null) {
            contact.setLastMessage(msg.getContent());
            contact.setLastTimestamp(msg.getTimestamp());
        }
    }

    public static void clearUnread(String contactId) {
        ChatContact contact = contacts.get(contactId);
        if (contact != null) contact.setUnreadCount(0);
    }

    public static void loadTestData() {
        try {
            loadTestContacts();
            loadConversation("npc_001_main");
            loadConversation("npc_001_quest");
            loadConversation("group_001_plan");
            loadConversation("npc_002_gate");
            loadConversation("npc_002_trade");
            loadConversation("npc_003_shop");
        } catch (Throwable e) {
            LaPluma.getLogger().log(Level.WARNING, "[Chat] Failed to load test data", e);
        }
    }

    private static void loadTestContacts() {
        try {
            IResource res = Minecraft.getMinecraft().getResourceManager()
                    .getResource(new ResourceLocation("lapluma", "chat/contacts.json"));
            InputStreamReader reader = new InputStreamReader(res.getInputStream(), StandardCharsets.UTF_8);
            Type listType = new TypeToken<List<ChatContact>>(){}.getType();
            List<ChatContact> list = GSON.fromJson(reader, listType);
            reader.close();
            if (list != null) setContacts(list);
            LaPluma.getLogger().log(Level.INFO, "[Chat] Loaded " + contacts.size() + " test contacts");
        } catch (Throwable e) {
            LaPluma.getLogger().log(Level.WARNING, "[Chat] contacts.json not found, skipping", e);
        }
    }

    private static void loadTestMessages(String fileId) {
        try {
            IResource res = Minecraft.getMinecraft().getResourceManager()
                    .getResource(new ResourceLocation("lapluma", "chat/messages_" + fileId + ".json"));
            InputStreamReader reader = new InputStreamReader(res.getInputStream(), StandardCharsets.UTF_8);
            JsonObject obj = GSON.fromJson(reader, JsonObject.class);
            reader.close();
            if (obj == null) return;
            String contactId = obj.get("contactId").getAsString();
            Type listType = new TypeToken<List<ChatMessage>>(){}.getType();
            List<ChatMessage> msgs = GSON.fromJson(obj.get("messages"), listType);
            if (msgs != null) setMessages(contactId, msgs);
            LaPluma.getLogger().log(Level.INFO, "[Chat] Loaded " + msgs.size() + " messages for " + contactId);
        } catch (Throwable e) {
            LaPluma.getLogger().log(Level.WARNING, "[Chat] messages_" + fileId + ".json not found, skipping", e);
        }
    }

    public static void parseContactsJson(String json) {
        Type listType = new TypeToken<List<ChatContact>>(){}.getType();
        List<ChatContact> list = GSON.fromJson(json, listType);
        if (list != null) setContacts(list);
    }

    public static void parseMessagesJson(String json) {
        JsonObject obj = GSON.fromJson(json, JsonObject.class);
        if (obj == null) return;
        String contactId = obj.get("contactId").getAsString();
        Type listType = new TypeToken<List<ChatMessage>>(){}.getType();
        List<ChatMessage> msgs = GSON.fromJson(obj.get("messages"), listType);
        if (msgs != null) setMessages(contactId, msgs);
    }

    public static void parseNewMessageJson(String json) {
        JsonObject obj = GSON.fromJson(json, JsonObject.class);
        if (obj == null) return;
        String contactId = obj.get("contactId").getAsString();
        ChatMessage msg = GSON.fromJson(obj.get("message"), ChatMessage.class);
        if (msg != null) addMessage(contactId, msg);
    }

    public static void parseUpdateContactJson(String json) {
        JsonObject obj = GSON.fromJson(json, JsonObject.class);
        if (obj == null) return;
        ChatContact contact = GSON.fromJson(obj.get("contact"), ChatContact.class);
        if (contact != null) updateContact(contact);
    }

    public static String buildReplyJson(String contactId, String messageId, String option) {
        JsonObject obj = new JsonObject();
        obj.addProperty("contactId", contactId);
        obj.addProperty("messageId", messageId);
        obj.addProperty("option", option);
        return obj.toString();
    }

    private static final int TYPING_MIN_TICKS = 20;
    private static final int TYPING_MAX_TICKS = 60;

    public static boolean advanceConversation(String contactId) {
        Conversation conv = getActiveConversation(contactId);
        if (conv == null || conv.finished || conv.isTyping) return false;
        if (!conv.hasNextMessage()) return false;

        ChatMessage next = conv.peekNext();
        if (PLAYER_ID.equals(next.getSenderId())) {
            if (next.getReplyOptions() != null && !next.getReplyOptions().isEmpty()) {
                return false;
            }
            conv.revealNext();
            playClickSound();
            updateContactPreview(contactId, next);
            if (!conv.hasNextMessage()) markConversationFinished(conv);
            return true;
        }

        int contentLen = next.getContent() != null ? next.getContent().length() : 0;
        int delay = Math.min(TYPING_MIN_TICKS + contentLen, TYPING_MAX_TICKS);
        conv.typingDelayTicks = delay;
        conv.isTyping = true;
        return true;
    }

    public static boolean skipTyping(String contactId) {
        Conversation conv = getActiveConversation(contactId);
        if (conv == null || conv.finished || !conv.isTyping) return false;
        conv.isTyping = false;
        conv.typingDelayTicks = 0;
        if (conv.hasNextMessage()) {
            ChatMessage msg = conv.peekNext();
            conv.revealNext();
            updateContactPreview(conv.contactId, msg);
            playClickSound();
            if (!conv.hasNextMessage()) {
                markConversationFinished(conv);
            }
            return true;
        }
        return false;
    }

    private static void playClickSound() {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.world != null && mc.player != null) {
            mc.world.playSound(mc.player.posX, mc.player.posY, mc.player.posZ,
                    LaPluma.Sounds.CLICK, SoundCategory.MASTER, 0.3f, 1.0f, false);
        }
    }

    public static void onReplySelected(String contactId, String branchId) {
        Conversation conv = getActiveConversation(contactId);
        if (conv == null || conv.finished) return;
        if (branchId != null && !branchId.isEmpty() && conv.branchIndexMap != null && conv.branchIndexMap.containsKey(branchId)) {
            int branchIndex = conv.branchIndexMap.get(branchId);
            conv.sequenceStart = branchIndex;
            conv.revealedInSequence = 0;
            conv.sequenceLength = computeSequenceLength(branchIndex, conv.allMessages, conv.branchStarts, conv.mainContinuationIndex);
        }
        if (conv.hasNextMessage()) {
            advanceConversation(contactId);
        }
    }

    public static void onReplySelected(String contactId, String branchId, boolean returnsToMain) {
        onReplySelected(contactId, branchId);
        Conversation conv = getActiveConversation(contactId);
        if (conv != null) {
            conv.returnToMain = returnsToMain;
        }
    }

    private static void updateContactPreview(String contactId, ChatMessage msg) {
        ChatContact contact = contacts.get(contactId);
        if (contact != null) {
            contact.setLastMessage(msg.getContent());
            contact.setLastTimestamp(msg.getTimestamp());
        }
    }

    private static void markConversationFinished(Conversation conv) {
        if (conv.returnToMain && conv.mainContinuationIndex >= 0
                && conv.mainContinuationIndex < conv.allMessages.size()) {
            conv.sequenceStart = conv.mainContinuationIndex;
            conv.revealedInSequence = 0;
            conv.sequenceLength = computeSequenceLength(conv.mainContinuationIndex, conv.allMessages, conv.branchStarts, -1);
            conv.returnToMain = false;
            conv.isTyping = false;
            return;
        }
        conv.finished = true;
        conv.isTyping = false;
        conv.status = "completed";
        ProxyPacketHandler.sendPacket(28, 0, conv.contactId);
        LaPluma.getLogger().log(Level.INFO, "[Chat] Conversation finished: " + conv.contactId);
    }

    public static void tick() {
        for (Map<String, Conversation> contactConvs : conversations.values()) {
            for (Conversation conv : contactConvs.values()) {
                if (conv.finished || !conv.isTyping) continue;
                conv.typingDelayTicks--;
                if (conv.typingDelayTicks <= 0) {
                    conv.isTyping = false;
                    if (conv.hasNextMessage()) {
                        conv.revealNext();
                        ChatMessage msg = conv.allMessages.get(conv.sequenceStart + conv.revealedInSequence - 1);
                        playClickSound();
                        updateContactPreview(conv.contactId, msg);
                        if (!conv.hasNextMessage()) {
                            markConversationFinished(conv);
                        }
                    }
                }
            }
        }
    }

    public static void loadConversation(String fileId) {
        try {
            IResource res = Minecraft.getMinecraft().getResourceManager()
                    .getResource(new ResourceLocation("lapluma", "chat/" + fileId + ".chat"));
            ChatJournalParser.Result parsed = ChatJournalParser.parse(res.getInputStream());
            String contactId = parsed.contactId != null ? parsed.contactId : "unknown";
            String conversationId = parsed.conversationId != null ? parsed.conversationId : fileId;
            String title = parsed.title != null ? parsed.title : conversationId;
            String status = parsed.status != null ? parsed.status : "completed";
            List<ChatMessage> msgs = parsed.messages;
            Conversation conv = new Conversation(conversationId, contactId, title, status, msgs, parsed.branchIndexMap, parsed.branchStarts, parsed.mainContinuationIndex);
            conversations.computeIfAbsent(contactId, k -> new LinkedHashMap<>()).put(conversationId, conv);
            if ("completed".equals(status)) {
                messages.put(contactId, new ArrayList<>(msgs));
            }
            LaPluma.getLogger().log(Level.INFO, "[Chat] Loaded conversation " + contactId + "/" + conversationId + " [" + status + "] " + msgs.size() + " messages");
        } catch (Throwable e) {
            LaPluma.getLogger().log(Level.WARNING, "[Chat] Failed to load conversation " + fileId, e);
        }
    }
}
