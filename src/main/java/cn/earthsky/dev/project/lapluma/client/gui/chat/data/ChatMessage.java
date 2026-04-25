package cn.earthsky.dev.project.lapluma.client.gui.chat.data;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class ChatMessage {
    private String id;
    private String senderId;
    private String senderName;
    private String senderAvatar;
    private String content;
    private long timestamp;
    private MessageType type;
    private List<String> replyOptions;
    private List<String> replyBranches;
    private List<Boolean> replyReturnsToMain;
    private transient long revealTime;

    public enum MessageType {
        TEXT, IMAGE, SYSTEM, TYPING
    }
}
