package com.xjjk.agent.memory.index;

import feign.FeignException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/** Knowledge Service 用户记忆索引接口的防腐层。 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KnowledgeMemoryIndexGateway implements MemoryIndexGateway {
    private final UserMemoryKnowledgeClient client;
    private final MemoryKnowledgeRequestSigner signer;

    @Override
    public void apply(MemoryIndexCommand command) {
        UserMemoryKnowledgeClient.IndexRequest request = toRequest(command);
        MemoryKnowledgeRequestSigner.SignedHeaders signed = signer.sign(
                MemoryKnowledgeRequestSigner.INDEX_PATH,
                command.tenantId(), command.userId(), request.payloadDigest());
        try {
            UserMemoryKnowledgeClient.ServiceResponse<UserMemoryKnowledgeClient.IndexData>
                    response = client.index(
                    command.tenantId(), command.userId(), signed.timestamp(), signed.nonce(),
                    signed.signature(), request);
            validateResponse(command.eventId(), response);
        } catch (FeignException | MemoryIndexUnavailableException exception) {
            log.warn("memory_index_gateway_failed eventId={}, exceptionType={}",
                    command.eventId(), exception.getClass().getSimpleName());
            throw exception instanceof MemoryIndexUnavailableException unavailable
                    ? unavailable : new MemoryIndexUnavailableException(exception);
        } catch (RuntimeException exception) {
            log.warn("memory_index_gateway_invalid_response eventId={}, exceptionType={}",
                    command.eventId(), exception.getClass().getSimpleName());
            throw new MemoryIndexUnavailableException(exception);
        }
    }

    private UserMemoryKnowledgeClient.IndexRequest toRequest(MemoryIndexCommand command) {
        return new UserMemoryKnowledgeClient.IndexRequest(
                command.eventId(), command.operation().name(), command.memoryGeneration(),
                command.memoryId(), command.memoryVersion(), command.sourceType(),
                command.category(), command.canonicalKey(), command.content(),
                command.confidence(), command.expiresAt());
    }

    private void validateResponse(
            String eventId,
            UserMemoryKnowledgeClient.ServiceResponse<UserMemoryKnowledgeClient.IndexData>
                    response) {
        if (response == null || !"SUCCESS".equals(response.code()) || response.data() == null
                || !eventId.equals(response.data().eventId())
                || !"APPLIED".equals(response.data().resultCode())) {
            throw new MemoryIndexUnavailableException();
        }
    }
}
