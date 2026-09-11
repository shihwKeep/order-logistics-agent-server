package com.xjjk.agent.memory.persistence.projection;

import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
public class UserMemoryListRow {
    private Long id;
    private String memoryId;
    private String category;
    private String content;
    private String retentionType;
    private Long version;
    private LocalDateTime updatedAt;
}
