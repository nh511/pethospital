package com.pethospital.ai;

import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public final class DeepSeekPromptSanitizer {
    private static final Pattern MOBILE = Pattern.compile("(?<!\\d)1[3-9]\\d{9}(?!\\d)");
    private static final Pattern EMAIL = Pattern.compile(
        "(?i)(?<![A-Z0-9._%+-])[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}(?![A-Z0-9._%+-])"
    );
    private static final Pattern PRC_ID = Pattern.compile(
        "(?<!\\d)\\d{6}(?:19|20)\\d{2}(?:0[1-9]|1[0-2])(?:0[1-9]|[12]\\d|3[01])\\d{3}[0-9Xx](?!\\d)"
    );

    public String sanitize(String content) {
        String value = MOBILE.matcher(content).replaceAll("[PHONE_REDACTED]");
        value = EMAIL.matcher(value).replaceAll("[EMAIL_REDACTED]");
        return PRC_ID.matcher(value).replaceAll("[ID_REDACTED]");
    }
}
