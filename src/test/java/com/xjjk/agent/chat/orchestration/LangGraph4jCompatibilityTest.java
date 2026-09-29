package com.xjjk.agent.chat.orchestration;

import org.bsc.langgraph4j.StateGraph;
import org.bsc.langgraph4j.state.AgentState;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.bsc.langgraph4j.action.AsyncNodeAction.node_async;
import static org.junit.jupiter.api.Assertions.assertEquals;

class LangGraph4jCompatibilityTest {

    @Test
    void compilesAndRunsMinimalStateGraph() throws Exception {
        var graph = new StateGraph<>(AgentState::new)
                .addNode("probe", node_async(state -> Map.of("probe", "ok")))
                .addEdge(StateGraph.START, "probe")
                .addEdge("probe", StateGraph.END)
                .compile();

        var result = graph.invoke(Map.of("input", "test")).orElseThrow();

        assertEquals("ok", result.value("probe").orElseThrow());
    }
}
