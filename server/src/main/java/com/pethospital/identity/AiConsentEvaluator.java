package com.pethospital.identity;

/** Shared gate for both synchronous AI requests and asynchronous outbound workers. */
public interface AiConsentEvaluator {
    boolean hasValidConsent(long userId);

    void requireValidConsent(long userId);
}
