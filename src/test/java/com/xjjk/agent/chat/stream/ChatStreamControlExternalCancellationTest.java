package com.xjjk.agent.chat.stream;

import com.xjjk.agent.chat.domain.MessageStatus;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

class ChatStreamControlExternalCancellationTest {

    @Test
    void observesCrossInstanceCancellationWhileTheRunnerIsWorking() {
        AtomicBoolean cancelledInRedis = new AtomicBoolean();
        ChatStreamControl control = new ChatStreamControl(cancelledInRedis::get);

        assertThat(control.isStopRequested()).isFalse();
        cancelledInRedis.set(true);

        assertThat(control.isStopRequested()).isTrue();
        assertThat(control.beginFinalization()).isEqualTo(MessageStatus.CANCELLED);
    }
}
