package com.xjjk.agent.chat.api.controller;

import com.xjjk.agent.chat.api.dto.ChatStreamRequest;
import com.xjjk.agent.chat.api.dto.ChatStreamStatusResponse;
import com.xjjk.agent.chat.config.ChatStreamProperties;
import com.xjjk.agent.chat.service.stream.ChatSseRelayService;
import com.xjjk.agent.chat.service.stream.ChatStreamOpenSession;
import com.xjjk.agent.chat.service.stream.ChatStreamService;
import com.xjjk.agent.identity.domain.AgentIdentity;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatStreamControllerTest {

    private static final AgentIdentity IDENTITY = new AgentIdentity(
            2L, "agent", "坐席", 3L, 1L);
    private static final String REQUEST_ID =
            "6f899318-0af5-4f2b-a593-84f6dac9dd1c";

    @Test
    void exposesTheReconnectContractOnInitialAndResumeResponses() throws Exception {
        ChatStreamService streams = mock(ChatStreamService.class);
        ChatSseRelayService relays = mock(ChatSseRelayService.class);
        ChatStreamProperties properties = new ChatStreamProperties(
                Duration.ofSeconds(30),
                Duration.ofSeconds(10),
                ChatStreamProperties.Replay.defaults(),
                ChatStreamProperties.Reconnect.defaults(),
                ChatStreamProperties.ModelRetry.defaults(),
                1000);
        ChatStreamController controller = new ChatStreamController(
                streams, relays, properties);
        Instant expiresAt = Instant.parse("2026-09-14T08:00:30Z");
        SseEmitter initialEmitter = new SseEmitter();
        ChatStreamRequest request = new ChatStreamRequest(
                null, "你好", null, REQUEST_ID);
        when(streams.open(request, IDENTITY)).thenReturn(
                new ChatStreamOpenSession(
                        initialEmitter, REQUEST_ID, expiresAt, true));
        MockHttpServletResponse initialResponse = new MockHttpServletResponse();

        assertThat(controller.stream(request, IDENTITY, initialResponse))
                .isSameAs(initialEmitter);
        assertHeaders(initialResponse, expiresAt);

        SseEmitter resumedEmitter = new SseEmitter();
        when(relays.status(IDENTITY, REQUEST_ID)).thenReturn(
                new ChatStreamStatusResponse(
                        "conversation-1", REQUEST_ID, "RUNNING",
                        expiresAt.minusSeconds(30), expiresAt, 7L, null, null));
        when(relays.resume(IDENTITY, REQUEST_ID, 7L)).thenReturn(resumedEmitter);
        MockHttpServletResponse resumedResponse = new MockHttpServletResponse();

        assertThat(controller.resume(
                REQUEST_ID, 7L, IDENTITY, resumedResponse)).isSameAs(resumedEmitter);
        assertHeaders(resumedResponse, expiresAt);
    }

    @Test
    void validatesReplayOwnershipBeforeCancellingARequest() {
        ChatStreamService streams = mock(ChatStreamService.class);
        ChatSseRelayService relays = mock(ChatSseRelayService.class);
        ChatStreamController controller = new ChatStreamController(
                streams,
                relays,
                new ChatStreamProperties(Duration.ofSeconds(30), Duration.ofSeconds(10)));

        controller.cancel(REQUEST_ID, IDENTITY);

        verify(relays).status(IDENTITY, REQUEST_ID);
        verify(streams).cancel(IDENTITY, REQUEST_ID);
    }

    private void assertHeaders(MockHttpServletResponse response, Instant expiresAt) {
        assertThat(response.getHeader("X-Chat-Request-Id")).isEqualTo(REQUEST_ID);
        assertThat(response.getHeader("X-Chat-Expires-At"))
                .isEqualTo(expiresAt.toString());
        assertThat(response.getHeader("X-Chat-Resumable")).isEqualTo("true");
        assertThat(response.getHeader("X-Chat-Test-Delay-Ms")).isEqualTo("1000");
        assertThat(response.getHeader("X-Chat-Reconnect-Max-Attempts")).isEqualTo("5");
        assertThat(response.getHeader("X-Accel-Buffering")).isEqualTo("no");
    }
}
