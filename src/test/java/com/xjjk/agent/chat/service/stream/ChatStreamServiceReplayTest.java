package com.xjjk.agent.chat.service.stream;

import com.xjjk.agent.chat.api.dto.ChatStreamRequest;
import com.xjjk.agent.chat.config.ChatStreamProperties;
import com.xjjk.agent.chat.replay.ChatReplayCreateResult;
import com.xjjk.agent.chat.replay.ChatReplayMetadata;
import com.xjjk.agent.chat.replay.ChatReplayRepository;
import com.xjjk.agent.chat.replay.ChatReplayUnavailableException;
import com.xjjk.agent.identity.domain.AgentIdentity;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Duration;
import java.util.concurrent.FutureTask;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doAnswer;

class ChatStreamServiceReplayTest {

    private static final AgentIdentity IDENTITY = new AgentIdentity(
            2L, "agent", "坐席", 3L, 1L);
    private static final String REQUEST_ID =
            "6f899318-0af5-4f2b-a593-84f6dac9dd1c";

    @Test
    void startsOnlyTheWinnerOfAnIdempotentReplayRequest() throws Exception {
        Fixture fixture = new Fixture();
        when(fixture.repository.available()).thenReturn(true);
        when(fixture.repository.create(any())).thenReturn(ChatReplayCreateResult.CREATED);
        when(fixture.relay.resume(IDENTITY, REQUEST_ID, 0L))
                .thenReturn(new SseEmitter());

        ChatStreamOpenSession opened = fixture.service.open(request(), IDENTITY);

        assertThat(opened.requestId()).isEqualTo(REQUEST_ID);
        assertThat(opened.resumable()).isTrue();
        ArgumentCaptor<ChatReplayMetadata> metadata =
                ArgumentCaptor.forClass(ChatReplayMetadata.class);
        verify(fixture.repository).create(metadata.capture());
        assertThat(metadata.getValue().conversationId()).isNull();
        verify(fixture.executor).execute(any(FutureTask.class));
    }

    @Test
    void duplicatePostOnlyAttachesToTheExistingProducer() throws Exception {
        Fixture fixture = new Fixture();
        when(fixture.repository.available()).thenReturn(true);
        when(fixture.repository.create(any())).thenReturn(ChatReplayCreateResult.EXISTING);
        when(fixture.relay.resume(IDENTITY, REQUEST_ID, 0L))
                .thenReturn(new SseEmitter());

        ChatStreamOpenSession opened = fixture.service.open(request(), IDENTITY);

        assertThat(opened.resumable()).isTrue();
        verify(fixture.executor, never()).execute(any(Runnable.class));
        verify(fixture.heartbeat, never()).start(any(), any());
    }

    @Test
    void relayFailureAfterProducerStartNeverFallsBackToASecondProducer() {
        Fixture fixture = new Fixture();
        when(fixture.repository.available()).thenReturn(true);
        when(fixture.repository.create(any())).thenReturn(ChatReplayCreateResult.CREATED);
        when(fixture.relay.resume(IDENTITY, REQUEST_ID, 0L))
                .thenThrow(new ChatReplayUnavailableException("Redis read failed"));

        assertThatThrownBy(() -> fixture.service.open(request(), IDENTITY))
                .isInstanceOf(ChatReplayUnavailableException.class);

        verify(fixture.executor, times(1)).execute(any(FutureTask.class));
    }

    @Test
    void redisCreationFailureBeforeBusinessWorkFallsBackToDirectSse() throws Exception {
        Fixture fixture = new Fixture();
        when(fixture.repository.available()).thenReturn(true);
        when(fixture.repository.create(any()))
                .thenThrow(new ChatReplayUnavailableException("Redis unavailable"));

        ChatStreamOpenSession opened = fixture.service.open(request(), IDENTITY);

        assertThat(opened.resumable()).isFalse();
        verify(fixture.executor, times(1)).execute(any(FutureTask.class));
        verify(fixture.relay, never()).resume(any(), any(), eq(0L));
    }

    @Test
    void explicitCancelUpdatesRedisAndInterruptsTheLocalProducer() {
        Fixture fixture = new Fixture();
        when(fixture.repository.requestCancel(IDENTITY, REQUEST_ID)).thenReturn(true);

        assertThat(fixture.service.cancel(IDENTITY, REQUEST_ID)
                .cancellationRequested()).isTrue();

        verify(fixture.repository).requestCancel(IDENTITY, REQUEST_ID);
    }

    @Test
    void localCancelStillWorksWhenRedisIsTemporarilyUnavailable() {
        Fixture fixture = new Fixture();
        ChatTurnJob job = new ChatTurnJob(
                REQUEST_ID,
                IDENTITY,
                new com.xjjk.agent.chat.stream.ChatStreamControl(),
                new FutureTask<>(() -> null),
                java.time.Instant.now().plusSeconds(30));
        fixture.registry.register(job);
        when(fixture.repository.requestCancel(IDENTITY, REQUEST_ID))
                .thenThrow(new ChatReplayUnavailableException("Redis unavailable"));

        assertThat(fixture.service.cancel(IDENTITY, REQUEST_ID)
                .cancellationRequested()).isTrue();
        assertThat(job.control().isStopRequested()).isTrue();
    }

    @Test
    void internalCompatibilityRequestUsesOneGeneratedIdEverywhere() throws Exception {
        Fixture fixture = new Fixture();
        when(fixture.repository.available()).thenReturn(false);
        doAnswer(invocation -> {
            ((Runnable) invocation.getArgument(0)).run();
            return null;
        }).when(fixture.executor).execute(any(Runnable.class));
        ChatStreamRequest requestWithoutId = new ChatStreamRequest(null, "你好", null);

        ChatStreamOpenSession opened = fixture.service.open(requestWithoutId, IDENTITY);

        ArgumentCaptor<ChatStreamRequest> executed =
                ArgumentCaptor.forClass(ChatStreamRequest.class);
        verify(fixture.runner).run(
                executed.capture(), eq(IDENTITY), any(), any(), eq(opened.requestId()));
        assertThat(executed.getValue().clientRequestId()).isEqualTo(opened.requestId());
    }

    private ChatStreamRequest request() {
        return new ChatStreamRequest(null, "你好", null, REQUEST_ID);
    }

    private static final class Fixture {
        private final ThreadPoolTaskExecutor executor = mock(ThreadPoolTaskExecutor.class);
        private final ChatTurnRunner runner = mock(ChatTurnRunner.class);
        private final ChatStreamProperties properties = new ChatStreamProperties(
                Duration.ofSeconds(30), Duration.ofSeconds(10));
        private final ChatSseHeartbeat heartbeat = mock(ChatSseHeartbeat.class);
        private final ChatReplayRepository repository = mock(ChatReplayRepository.class);
        private final ChatSseRelayService relay = mock(ChatSseRelayService.class);
        private final ChatTurnJobRegistry registry = new ChatTurnJobRegistry();
        private final ChatTurnDeadline deadline = mock(ChatTurnDeadline.class);
        private final ChatStreamService service;

        private Fixture() {
            when(heartbeat.start(any(), any())).thenReturn(mock(ChatSseHeartbeat.Lease.class));
            when(deadline.schedule(any(), any(), any())).thenReturn(() -> { });
            service = new ChatStreamService(
                    executor, runner, properties, heartbeat,
                    repository, relay, registry, deadline);
        }
    }
}
