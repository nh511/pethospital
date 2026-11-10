package com.pethospital.health;

import java.net.URI;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import org.springframework.web.util.HtmlUtils;

/**
 * Small allow-list sanitizer for authored guide HTML. Text is escaped first and only
 * canonical, explicitly allowed tags are emitted, so malformed attributes cannot survive.
 */
@Component
public final class GuideHtmlSanitizer {
    private static final Set<String> SIMPLE_TAGS = Set.of(
        "p", "br", "strong", "em", "u", "ul", "ol", "li",
        "h2", "h3", "h4", "blockquote", "code", "pre"
    );
    private static final Pattern TAG = Pattern.compile(
        "(?is)<\\s*(/?)\\s*([a-z][a-z0-9]*)\\s*([^>]*)>"
    );
    private static final Pattern DANGEROUS_BLOCK = Pattern.compile(
        "(?is)<\\s*(script|style|iframe|object|embed|svg|math|template|form)\\b[^>]*>.*?<\\s*/\\s*\\1\\s*>"
    );
    private static final Pattern LINK_ATTRIBUTES = Pattern.compile(
        "(?is)^href\\s*=\\s*(['\"])(.*?)\\1\\s*$"
    );

    public String sanitize(String html) {
        if (html == null) {
            return null;
        }
        String cleaned = html.replace("\u0000", "");
        Matcher dangerous = DANGEROUS_BLOCK.matcher(cleaned);
        while (dangerous.find()) {
            cleaned = dangerous.replaceAll("");
            dangerous = DANGEROUS_BLOCK.matcher(cleaned);
        }

        StringBuilder safe = new StringBuilder(cleaned.length());
        Matcher tags = TAG.matcher(cleaned);
        int cursor = 0;
        int safeAnchorDepth = 0;
        while (tags.find()) {
            safe.append(HtmlUtils.htmlEscape(cleaned.substring(cursor, tags.start())));
            String closing = tags.group(1);
            String name = tags.group(2).toLowerCase();
            String attributes = tags.group(3).strip();
            if (SIMPLE_TAGS.contains(name)) {
                if (!("br".equals(name) && !closing.isEmpty())) {
                    safe.append('<').append(closing).append(name).append('>');
                }
            } else if ("a".equals(name)) {
                if (!closing.isEmpty()) {
                    if (attributes.isEmpty() && safeAnchorDepth > 0) {
                        safe.append("</a>");
                        safeAnchorDepth--;
                    }
                } else if (appendLink(safe, attributes)) {
                    safeAnchorDepth++;
                }
            }
            cursor = tags.end();
        }
        safe.append(HtmlUtils.htmlEscape(cleaned.substring(cursor)));
        return safe.toString().strip();
    }

    private static boolean appendLink(StringBuilder safe, String attributes) {
        Matcher matcher = LINK_ATTRIBUTES.matcher(attributes);
        if (!matcher.matches()) {
            return false;
        }
        try {
            URI uri = URI.create(matcher.group(2).strip());
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null) {
                return false;
            }
            safe.append("<a href=\"")
                .append(HtmlUtils.htmlEscape(uri.toASCIIString()))
                .append("\" rel=\"noopener noreferrer\">");
            return true;
        } catch (IllegalArgumentException ignored) {
            // Invalid links are reduced to text by omitting their anchor tag.
            return false;
        }
    }
}
