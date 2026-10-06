package com.xjjk.agent.common.exception;

import com.xjjk.agent.common.api.ApiErrorCode;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Controller;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class GlobalExceptionHandlerSseTest {

    @Test
    void returnsJsonUnauthorizedResponseForMissingCredentialOnSseRequest()
            throws Exception {
        HandlerInterceptor authentication = new HandlerInterceptor() {
            @Override
            public boolean preHandle(
                    jakarta.servlet.http.HttpServletRequest request,
                    jakarta.servlet.http.HttpServletResponse response,
                    Object handler
            ) {
                throw new BusinessException(ApiErrorCode.AUTH_HEADER_MISSING);
            }
        };
        MockMvc mockMvc = MockMvcBuilders
                .standaloneSetup(new SseEndpoint())
                .setControllerAdvice(new GlobalExceptionHandler())
                .addInterceptors(authentication)
                .build();

        mockMvc.perform(post("/test-sse")
                        .accept(MediaType.TEXT_EVENT_STREAM)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(
                        MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value("AUTH_HEADER_MISSING"));
    }

    @Controller
    @RequestMapping("/test-sse")
    static class SseEndpoint {

        @PostMapping(produces = MediaType.TEXT_EVENT_STREAM_VALUE)
        @ResponseBody
        String stream() {
            return "ok";
        }
    }
}
