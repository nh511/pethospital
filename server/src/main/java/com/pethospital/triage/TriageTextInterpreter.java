package com.pethospital.triage;

import com.fasterxml.jackson.databind.JsonNode;
import com.pethospital.triage.TriageDtos.Observation;
import com.pethospital.triage.TriageDtos.ObservationContext;
import com.pethospital.triage.TriageDtos.TriageInput;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public final class TriageTextInterpreter {
    private static final Map<String, List<String>> ALIASES = Map.ofEntries(
        Map.entry("BREATHING_DIFFICULTY", List.of("呼吸困难", "喘不上气", "呼吸急促")),
        Map.entry("OPEN_MOUTH_BREATHING", List.of("张口呼吸", "张嘴呼吸")),
        Map.entry("UNCONTROLLED_BLEEDING", List.of("无法止血", "持续出血", "大量出血")),
        Map.entry("SEIZURE", List.of("抽搐", "癫痫发作", "惊厥")),
        Map.entry("UNRESPONSIVE", List.of("无反应", "叫不醒", "失去意识")),
        Map.entry("SUSPECTED_POISONING", List.of("疑似中毒", "误食毒物", "吃了毒物"))
    );
    private static final List<String> NEGATIONS = List.of("没有", "并无", "未见", "不是", "无明显", "不再");
    private static final List<String> HYPOTHETICAL = List.of("如果", "假如", "要是", "会不会", "担心会", "万一");
    private static final List<String> HISTORY = List.of("之前", "以前", "曾经", "昨天", "前几天");
    private static final List<String> RESOLVED = List.of("现在好了", "已经好了", "已恢复", "已经停止", "已经没有", "现已正常");
    private static final Pattern DURATION = Pattern.compile("(\\d{1,4})\\s*(分钟|小时|天)");

    public TriageInput interpret(
        JsonNode predicate,
        String speciesCode,
        String content,
        Map<String, String> screeningAnswers
    ) {
        String normalizedContent = content == null ? "" : content.strip();
        Map<String, Observation> symptoms = new LinkedHashMap<>();
        for (String code : symptomCodes(predicate)) {
            Mention mention = findMention(code, normalizedContent);
            if (mention != null) {
                symptoms.put(code, new Observation(
                    context(normalizedContent, mention),
                    durationMinutes(normalizedContent, mention),
                    excerpt(normalizedContent, mention)
                ));
            }
        }
        return new TriageInput(speciesCode, symptoms, screeningAnswers);
    }

    private static Set<String> symptomCodes(JsonNode predicate) {
        Set<String> result = new LinkedHashSet<>();
        collectCodes(predicate.path("expression"), result);
        return Set.copyOf(result);
    }

    private static void collectCodes(JsonNode node, Set<String> result) {
        if (node == null || !node.isObject()) {
            return;
        }
        String fact = node.path("fact").asText();
        if (("SYMPTOM".equals(fact) || "DURATION_MINUTES".equals(fact)) && node.path("code").isTextual()) {
            result.add(node.path("code").asText().strip().toUpperCase(Locale.ROOT));
        }
        JsonNode children = node.path("children");
        if (children.isArray()) {
            children.forEach(child -> collectCodes(child, result));
        }
    }

    private static Mention findMention(String code, String content) {
        List<String> terms = new ArrayList<>(ALIASES.getOrDefault(code, List.of()));
        terms.add(code);
        terms.add(code.replace('_', ' '));
        String upper = content.toUpperCase(Locale.ROOT);
        Mention earliest = null;
        for (String term : terms) {
            int index = upper.indexOf(term.toUpperCase(Locale.ROOT));
            if (index >= 0 && (earliest == null || index < earliest.index())) {
                earliest = new Mention(index, term.length());
            }
        }
        return earliest;
    }

    private static ObservationContext context(String content, Mention mention) {
        int prefixStart = Math.max(0, mention.index() - 12);
        int suffixEnd = Math.min(content.length(), mention.index() + mention.length() + 16);
        String prefix = content.substring(prefixStart, mention.index());
        String window = content.substring(prefixStart, suffixEnd);
        if (containsAny(window, HISTORY) && containsAny(window, RESOLVED)) {
            return ObservationContext.HISTORICAL_RESOLVED;
        }
        if (containsAny(prefix, HYPOTHETICAL)) {
            return ObservationContext.HYPOTHETICAL;
        }
        if (containsAny(prefix, NEGATIONS)) {
            return ObservationContext.ABSENT;
        }
        return ObservationContext.PRESENT;
    }

    private static Integer durationMinutes(String content, Mention mention) {
        int start = Math.max(0, mention.index() - 12);
        int end = Math.min(content.length(), mention.index() + mention.length() + 20);
        Matcher matcher = DURATION.matcher(content.substring(start, end));
        if (!matcher.find()) {
            return null;
        }
        long amount = Long.parseLong(matcher.group(1));
        long minutes = switch (matcher.group(2)) {
            case "小时" -> amount * 60;
            case "天" -> amount * 1440;
            default -> amount;
        };
        return minutes <= 525_600 ? (int) minutes : null;
    }

    private static String excerpt(String content, Mention mention) {
        int start = Math.max(0, mention.index() - 24);
        int end = Math.min(content.length(), mention.index() + mention.length() + 48);
        return content.substring(start, end);
    }

    private static boolean containsAny(String value, List<String> terms) {
        return terms.stream().anyMatch(value::contains);
    }

    private record Mention(int index, int length) {
    }
}
