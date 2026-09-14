package com.xjjk.agent.chat.replay;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ChatReplayLifecycleResultTest {

    @Test
    void distinguishesCreatedAndExistingRequests() {
        assertThat(ChatReplayLifecycleResult.created(List.of("CREATED")))
                .isEqualTo(ChatReplayCreateResult.CREATED);
        assertThat(ChatReplayLifecycleResult.created(List.of("EXISTING")))
                .isEqualTo(ChatReplayCreateResult.EXISTING);
    }

    @Test
    void doesNotDiscloseARequestOwnedByAnotherIdentity() {
        assertThatThrownBy(() -> ChatReplayLifecycleResult.created(
                List.of("IDENTITY_MISMATCH")))
                .isInstanceOf(ChatReplayUnavailableException.class);
    }

    @Test
    void distinguishesAcceptedAndAlreadyTerminalCancellation() {
        assertThat(ChatReplayLifecycleResult.cancelled(List.of("REQUESTED"))).isTrue();
        assertThat(ChatReplayLifecycleResult.cancelled(List.of("TERMINAL"))).isFalse();
    }
}
