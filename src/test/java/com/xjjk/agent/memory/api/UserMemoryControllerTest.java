package com.xjjk.agent.memory.api;

import com.xjjk.agent.identity.domain.AgentIdentity;
import com.xjjk.agent.memory.api.dto.UpdateUserMemorySettingRequest;
import com.xjjk.agent.memory.api.dto.UserMemoryPageResponse;
import com.xjjk.agent.memory.api.dto.UserMemorySettingResponse;
import com.xjjk.agent.memory.service.UserMemoryQueryService;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.RequestMapping;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UserMemoryControllerTest {

    @Test
    void exposesOnlyCurrentUserMemoryOperations() {
        UserMemoryQueryService service = mock(UserMemoryQueryService.class);
        UserMemoryController controller = new UserMemoryController(service);
        AgentIdentity identity = new AgentIdentity(2L, "account", "name", 3L, 1L);
        UserMemoryPageResponse page = new UserMemoryPageResponse(List.of(), null, false);
        when(service.list(identity, null, 10)).thenReturn(page);
        when(service.getSetting(identity)).thenReturn(new UserMemorySettingResponse(true));
        when(service.updateSetting(identity, false)).thenReturn(new UserMemorySettingResponse(false));

        assertThat(controller.list(identity, null, 10).data()).isSameAs(page);
        assertThat(controller.getSetting(identity).data().autoExtractEnabled()).isTrue();
        assertThat(controller.updateSetting(identity,
                new UpdateUserMemorySettingRequest(false)).data().autoExtractEnabled()).isFalse();
        verify(service).list(identity, null, 10);
        verify(service).updateSetting(identity, false);

        assertThat(UserMemoryController.class.getAnnotation(RequestMapping.class).value())
                .containsExactly("/api/v1/me");
    }
}
