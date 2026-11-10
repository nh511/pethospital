package com.pethospital.health;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class GuideHtmlSanitizerTest {
    private final GuideHtmlSanitizer sanitizer = new GuideHtmlSanitizer();

    @Test
    void keepsOnlyCanonicalAllowListedMarkup() {
        assertThat(sanitizer.sanitize("<h2>标题</h2><p onclick='x()'>正文<strong>重点</strong></p>"))
            .isEqualTo("<h2>标题</h2><p>正文<strong>重点</strong></p>");
    }

    @Test
    void removesExecutableBlocksAndUnsafeLinks() {
        assertThat(sanitizer.sanitize(
            "<script>alert(1)</script><a href=\"javascript:alert(2)\">坏链接</a>"
                + "<a href='https://example.com/a?q=1'>来源</a>"
        )).isEqualTo("坏链接<a href=\"https://example.com/a?q=1\" rel=\"noopener noreferrer\">来源</a>");
    }
}
