package cn.earthsky.dev.project.lapluma.common.text;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.Getter;
import lombok.Setter;

@Data @AllArgsConstructor
public class AVGCharacter {
    String identity;
    int position;
    @Setter boolean dimmed = false;
    @Setter EntityMode entityMode = EntityMode.NONE;
    @Setter float entityScale = 1.0f;
    @Setter int entityYOffset = 0;

    @Getter
    public enum EntityMode {
        NONE,
        CREATE,
        PLAYER,
        DISPLAY_NAME,
        UUID
    }

    public boolean isEntity() {
        return entityMode != EntityMode.NONE;
    }

    public AVGCharacter(String identity, int position, boolean dimmed) {
        this.identity = identity;
        this.position = position;
        this.dimmed = dimmed;
        this.entityMode = EntityMode.NONE;
        this.entityScale = 1.0f;
        this.entityYOffset = 0;
    }
}
