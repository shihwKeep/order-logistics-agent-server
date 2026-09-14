package com.xjjk.agent.chat.service.stream;

import com.xjjk.agent.chat.domain.MessageStatus;
import com.xjjk.agent.chat.stream.ChatStreamControl;
import com.xjjk.agent.identity.domain.AgentIdentity;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

class ChatTurnJobRegistryTest {

    private static final AgentIdentity OWNER = new AgentIdentity(
            2L, "agent", "坐席", 3L, 1L);
    private static final String REQUEST_ID =
            "6f899318-0af5-4f2b-a593-84f6dac9dd1c";

    @Test
    void disconnectingRelayDoesNotStopDetachedJob() {
        ChatStreamControl control = new ChatStreamControl();
        ChatTurnJob job = new ChatTurnJob(
                REQUEST_ID,
                OWNER,
                control,
                new FutureTask<>(() -> null),
                Instant.now().plusSeconds(30));
        ChatTurnJobRegistry registry = new ChatTurnJobRegistry();

        assertThat(registry.register(job)).isTrue();
        job.relayDisconnected();

        assertThat(control.isStopRequested()).isFalse();
        assertThat(registry.find(REQUEST_ID)).containsSame(job);
    }

    @Test
    void explicitCancelStopsOnlyOwnedRunningJob() {
        ChatStreamControl control = new ChatStreamControl();
        ChatTurnJob job = new ChatTurnJob(
                REQUEST_ID,
                OWNER,
                control,
                new FutureTask<>(() -> null),
                Instant.now().plusSeconds(30));
        ChatTurnJobRegistry registry = new ChatTurnJobRegistry();
        registry.register(job);

        AgentIdentity otherUser = new AgentIdentity(
                9L, "other", "其他坐席", 3L, 1L);
        assertThat(registry.cancel(otherUser, REQUEST_ID)).isFalse();
        assertThat(control.isStopRequested()).isFalse();

        assertThat(registry.cancel(OWNER, REQUEST_ID)).isTrue();
        assertThat(control.isStopRequested()).isTrue();
        assertThat(control.beginFinalization()).isEqualTo(MessageStatus.CANCELLED);
    }

    @Test
    void duplicateRequestIdCannotReplaceTheRunningJob() {
        ChatTurnJobRegistry registry = new ChatTurnJobRegistry();
        ChatTurnJob first = job(new ChatStreamControl());
        ChatTurnJob duplicate = job(new ChatStreamControl());

        assertThat(registry.register(first)).isTrue();
        assertThat(registry.register(duplicate)).isFalse();
        assertThat(registry.find(REQUEST_ID)).containsSame(first);
        assertThat(registry.remove(REQUEST_ID, first)).isTrue();
        assertThat(registry.find(REQUEST_ID)).isEmpty();
    }

    @Test
    void removingFinishedJobClosesItsTaskHeartbeatLease() {
        ChatTurnJobRegistry registry = new ChatTurnJobRegistry();
        ChatTurnJob job = job(new ChatStreamControl());
        AtomicBoolean heartbeatClosed = new AtomicBoolean();
        job.bindHeartbeat(() -> heartbeatClosed.set(true));
        registry.register(job);

        assertThat(registry.remove(REQUEST_ID, job)).isTrue();

        assertThat(heartbeatClosed).isTrue();
    }

    private ChatTurnJob job(ChatStreamControl control) {
        return new ChatTurnJob(
                REQUEST_ID,
                OWNER,
                control,
                new FutureTask<>(() -> null),
                Instant.now().plusSeconds(30));
    }
}
