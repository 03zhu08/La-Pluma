package cn.earthsky.dev.project.lapluma.client.gui.chat.data;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class ChatContact {
    private String id;
    private String name;
    private String skin;
    private String faction;
    private String lastMessage;
    private long lastTimestamp;
    private int unreadCount;
    private boolean isGroup;
    private List<String> members;
}
