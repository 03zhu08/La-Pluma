package cn.earthsky.dev.project.lapluma.client.gui.chat.data;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class ChatJournalParser {

    public static class Result {
        public String conversationId;
        public String contactId;
        public String title;
        public String status;
        public List<ChatMessage> messages = new ArrayList<>();
        public Map<String, Integer> branchIndexMap = new LinkedHashMap<>();
        public Set<Integer> branchStarts = new HashSet<>();
        public int mainContinuationIndex = -1;
        // Contact metadata from .chat headers
        public String skin;
        public String faction;
        public boolean isGroup;
        public List<String> members = new ArrayList<>();
        public String contactName;
    }

    public static Result parse(InputStream in) throws Exception {
        Result r = new Result();
        BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));

        String line;
        ChatMessage currentMsg = null;
        StringBuilder contentBuf = new StringBuilder();
        List<String> currentOptions = new ArrayList<>();
        List<String> currentBranches = new ArrayList<>();
        List<Boolean> currentReturnsToMain = new ArrayList<>();

        while ((line = reader.readLine()) != null) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) continue;

            if (trimmed.startsWith("#")) {
                int space = trimmed.indexOf(' ');
                if (space > 1) {
                    String key = trimmed.substring(1, space);
                    String value = trimmed.substring(space + 1).trim();
                    switch (key) {
                        case "conv": r.conversationId = value; break;
                        case "contact": r.contactId = value; break;
                        case "title": r.title = value; break;
                        case "status": r.status = value; break;
                        case "skin": r.skin = value; break;
                        case "faction": r.faction = value.isEmpty() ? null : value; break;
                        case "group": r.isGroup = "true".equals(value); break;
                        case "members": r.members = value.isEmpty() ? new ArrayList<>() : Arrays.asList(value.split("\\s*,\\s*")); break;
                        case "name": r.contactName = value; break;
                    }
                }
            } else if (trimmed.startsWith("=")) {
                flushMessage(currentMsg, contentBuf, currentOptions, currentBranches, currentReturnsToMain, r);
                currentMsg = null;
                contentBuf.setLength(0);
                currentOptions.clear();
                currentBranches.clear();
                currentReturnsToMain.clear();

                String branchId = trimmed.substring(1).trim();
                if ("main".equals(branchId)) {
                    r.mainContinuationIndex = r.messages.size();
                } else {
                    r.branchIndexMap.put(branchId, r.messages.size());
                    r.branchStarts.add(r.messages.size());
                }
            } else if (trimmed.startsWith("@")) {
                flushMessage(currentMsg, contentBuf, currentOptions, currentBranches, currentReturnsToMain, r);

                String senderPart = trimmed.substring(1).trim();
                int pipe = senderPart.indexOf('|');
                String senderId;
                String senderName;
                if (pipe >= 0) {
                    senderId = senderPart.substring(0, pipe);
                    senderName = senderPart.substring(pipe + 1);
                } else {
                    senderId = senderPart;
                    senderName = senderPart;
                }

                currentMsg = new ChatMessage();
                currentMsg.setId("msg_" + r.messages.size());
                currentMsg.setSenderId(senderId);
                currentMsg.setSenderName(senderName);
                if ("SYSTEM".equals(senderId) || "$system".equals(senderId)) {
                    currentMsg.setType(ChatMessage.MessageType.SYSTEM);
                } else {
                    currentMsg.setType(ChatMessage.MessageType.TEXT);
                }
                currentMsg.setTimestamp(System.currentTimeMillis());
                contentBuf.setLength(0);
                currentOptions.clear();
                currentBranches.clear();
                currentReturnsToMain.clear();
            } else if (trimmed.startsWith("+")) {
                String optText = trimmed.substring(1).trim();
                boolean returnsToMain = false;
                int pipeIdx = optText.lastIndexOf('|');
                if (pipeIdx >= 0) {
                    String suffix = optText.substring(pipeIdx + 1).trim();
                    if ("main".equals(suffix)) {
                        returnsToMain = true;
                        optText = optText.substring(0, pipeIdx).trim();
                    }
                }
                String branchId = null;
                int arrow = optText.indexOf("->");
                if (arrow >= 0) {
                    branchId = optText.substring(arrow + 2).trim();
                    optText = optText.substring(0, arrow).trim();
                }
                currentOptions.add(optText);
                currentBranches.add(branchId);
                currentReturnsToMain.add(returnsToMain);
            } else {
                if (contentBuf.length() > 0) contentBuf.append('\n');
                contentBuf.append(trimmed);
            }
        }

        flushMessage(currentMsg, contentBuf, currentOptions, currentBranches, currentReturnsToMain, r);

        reader.close();
        return r;
    }

    private static void flushMessage(ChatMessage msg, StringBuilder content, List<String> options,
                                      List<String> branches, List<Boolean> returnsToMain, Result r) {
        if (msg == null) return;
        msg.setContent(content.toString());
        if (!options.isEmpty()) {
            msg.setReplyOptions(new ArrayList<>(options));
            msg.setReplyBranches(new ArrayList<>(branches));
            msg.setReplyReturnsToMain(new ArrayList<>(returnsToMain));
        }
        r.messages.add(msg);
    }
}
