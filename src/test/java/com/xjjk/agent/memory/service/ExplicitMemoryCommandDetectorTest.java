package com.xjjk.agent.memory.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ExplicitMemoryCommandDetectorTest {

    private final ExplicitMemoryCommandDetector detector = new ExplicitMemoryCommandDetector(512);

    @Test
    void detectsAnchoredNormalAndPermanentCommands() {
        assertThat(detector.detect("请记住以后回答简短一些"))
                .contains(new ExplicitMemoryCommandDetector.CommandText("以后回答简短一些", false));
        assertThat(detector.detect("请永久记住：叫我老师。"))
                .contains(new ExplicitMemoryCommandDetector.CommandText("叫我老师", true));
        assertThat(detector.detect("以后请 回答简短一些"))
                .contains(new ExplicitMemoryCommandDetector.CommandText("回答简短一些", false));
        assertThat(detector.detect("请永远记住我叫小石"))
                .contains(new ExplicitMemoryCommandDetector.CommandText("我叫小石", true));
        assertThat(detector.detect("请帮我长期保留：我喜欢简洁回答"))
                .contains(new ExplicitMemoryCommandDetector.CommandText("我喜欢简洁回答", true));
    }

    @Test
    void doesNotTreatDiscussionOrMixedIntentAsACommand() {
        assertThat(detector.detect("我觉得你应该记住这个问题吗？")).isEmpty();
        assertThat(detector.detect("请记住回答简短，顺便帮我查订单")).isEmpty();
        assertThat(detector.detect("请记住回答简短，另外帮我查物流")).isEmpty();
        assertThat(detector.detect("请记住回答简短，同时帮我退款")).isEmpty();
        assertThat(detector.detect("请记住我一直保存阅读笔记"))
                .contains(new ExplicitMemoryCommandDetector.CommandText("我一直保存阅读笔记", false));
    }

    @Test
    void rejectsBlankAndOversizedPayloads() {
        assertThat(detector.detect("请记住。 ")).isEmpty();
        assertThat(detector.detect("请记住" + "好".repeat(513))).isEmpty();
    }
}
