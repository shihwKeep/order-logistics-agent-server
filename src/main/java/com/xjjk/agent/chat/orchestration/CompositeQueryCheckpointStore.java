package com.xjjk.agent.chat.orchestration;

import java.util.Optional;

public interface CompositeQueryCheckpointStore {
    Optional<CompositeQueryCheckpoint> load(String threadId);

    void save(CompositeQueryCheckpoint checkpoint);

    void delete(String threadId);
}
