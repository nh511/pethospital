package com.pethospital.testing;

import java.util.Objects;

@FunctionalInterface
public interface FailureInjector {
    void checkpoint(String checkpoint);

    static FailureInjector none() {
        return checkpoint -> {
        };
    }

    static FailureInjector failAt(String expectedCheckpoint) {
        Objects.requireNonNull(expectedCheckpoint, "expectedCheckpoint");
        return checkpoint -> {
            if (expectedCheckpoint.equals(checkpoint)) {
                throw new InjectedFailureException(checkpoint);
            }
        };
    }

    final class InjectedFailureException extends RuntimeException {
        public InjectedFailureException(String checkpoint) {
            super("Injected transaction failure at checkpoint: " + checkpoint);
        }
    }
}
