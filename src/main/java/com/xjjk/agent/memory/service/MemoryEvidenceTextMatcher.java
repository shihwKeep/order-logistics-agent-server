package com.xjjk.agent.memory.service;

/** 用户原文证据匹配；仅容忍 Unicode 字母大小写差异。 */
final class MemoryEvidenceTextMatcher {

    private MemoryEvidenceTextMatcher() {
    }

    static boolean contains(String text, String evidence) {
        return indexOf(text, evidence, 0) >= 0;
    }

    static int indexOf(String text, String evidence, int fromIndex) {
        if (text == null || evidence == null || evidence.isEmpty()) {
            return -1;
        }
        int start = Math.max(0, fromIndex);
        int lastStart = text.length() - evidence.length();
        for (int index = start; index <= lastStart; index++) {
            if (text.regionMatches(true, index, evidence, 0, evidence.length())) {
                return index;
            }
        }
        return -1;
    }
}
