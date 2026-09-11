package com.xjjk.agent.memory.persistence;

import com.xjjk.agent.memory.persistence.mapper.UserMemoryMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class UserMemoryMapperContractTest {

    private static final List<String> OWNER_SCOPED_METHODS = List.of(
            "selectActiveByKeyForUpdate",
            "selectVisiblePage",
            "supersedeOwnedActive",
            "softDeleteOwned",
            "clearOwnedExplicit",
            "clearOwnedGeneration"
    );

    @Test
    void everyMemoryOperationCarriesAuthenticatedOwnerParameters() {
        for (String methodName : OWNER_SCOPED_METHODS) {
            Method method = findMethod(methodName);
            Set<String> parameterNames = Arrays.stream(method.getParameters())
                    .map(parameter -> parameter.getAnnotation(Param.class))
                    .filter(annotation -> annotation != null)
                    .map(Param::value)
                    .collect(Collectors.toSet());

            assertThat(parameterNames)
                    .as("owner boundary for %s", methodName)
                    .contains("tenantId", "userId", "generation");
        }
    }

    @Test
    void visibleListingIsExplicitActiveKeysetPagination() {
        Method method = findMethod("selectVisiblePage");
        String sql = String.join("\n", method.getAnnotation(Select.class).value());

        assertThat(sql)
                .contains("source_type = 'USER_EXPLICIT'")
                .contains("visibility = 'VISIBLE'")
                .contains("status = 'ACTIVE'")
                .contains("updated_at <")
                .contains("id <")
                .contains("ORDER BY updated_at DESC, id DESC")
                .doesNotContainIgnoringCase("OFFSET");
    }

    @Test
    void destructiveUpdatesEnforceOwnerInSql() {
        for (String methodName : List.of(
                "supersedeOwnedActive",
                "softDeleteOwned",
                "clearOwnedExplicit",
                "clearOwnedGeneration")) {
            Method method = findMethod(methodName);
            String sql = String.join("\n", method.getAnnotation(Update.class).value());

            assertThat(sql)
                    .as("SQL owner boundary for %s", methodName)
                    .contains("tenant_id = #{tenantId}")
                    .contains("user_id = #{userId}")
                    .contains("memory_generation = #{generation}");
        }
    }

    private Method findMethod(String name) {
        return Arrays.stream(UserMemoryMapper.class.getDeclaredMethods())
                .filter(method -> method.getName().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing mapper method: " + name));
    }
}
