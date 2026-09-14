package com.xjjk.agent.chat.replay;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ChatReplayScriptResultTest {

    @Test
    void returnsTheSequenceAssignedAtomicallyByRedis() {
        assertThat(ChatReplayScriptResult.appendSequence(List.of("OK", "12")))
                .isEqualTo(12L);
    }

    @Test
    void mapsCapacityAndInfrastructureFailuresToDifferentExceptions() {
        assertThatThrownBy(() -> ChatReplayScriptResult.appendSequence(List.of("LIMIT")))
                .isInstanceOf(ChatReplayLimitException.class);
        assertThatThrownBy(() -> ChatReplayScriptResult.appendSequence(null))
                .isInstanceOf(ChatReplayUnavailableException.class);
    }

    @Test
    void rejectsAppendingAfterATerminalEvent() {
        assertThatThrownBy(() -> ChatReplayScriptResult.appendSequence(List.of("TERMINAL")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("终态");
    }
}
