package org.example.springtestci.ai.dto;

import java.util.List;

public record AiAskResponse(
    String query, String answer, boolean isFallback, List<String> usedTools) {}
