package com.xjjk.agent.chat.service.memory;

import com.xjjk.agent.chat.observation.ChatHistoryCacheMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class ChatHistoryCacheWarmListenerTest {

    @Mock
    private ThreadPoolTaskExecutor executor;

    @Mock
    private ChatHistoryCacheWarmService warmService;

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private ChatHistoryCacheWarmListener listener;
    private ChatHistoryChangedEvent event;

    @BeforeEach
    void setUp() {
        listener = new ChatHistoryCacheWarmListener(
                executor,
                warmService,
                new ChatHistoryCacheMetrics(registry)
        );
        event = new ChatHistoryChangedEvent(
                1, 10567, "conversation-1", 9, 18);
    }

    @Test
    void committedEventIsSubmittedToDedicatedExecutor() {
        listener.afterCommit(event);

        ArgumentCaptor<Runnable> task =
                ArgumentCaptor.forClass(Runnable.class);
        verify(executor).execute(task.capture());
        task.getValue().run();
        verify(warmService).warm(event);
    }

    @Test
    void listenerRunsOnlyAfterTransactionCommit() throws Exception {
        Method method = ChatHistoryCacheWarmListener.class.getMethod(
                "afterCommit",
                ChatHistoryChangedEvent.class
        );

        assertThat(method.getAnnotation(
                TransactionalEventListener.class).phase())
                .isEqualTo(TransactionPhase.AFTER_COMMIT);
    }

    @Test
    void rejectedWarmTaskDoesNotEscapeCommitCallback() {
        doThrow(new TaskRejectedException("full"))
                .when(executor)
                .execute(any(Runnable.class));

        assertThatCode(() -> listener.afterCommit(event))
                .doesNotThrowAnyException();
        assertThat(registry.get(
                "chat.history.cache.warm.rejected")
                .counter().count()).isEqualTo(1.0);
    }
}
