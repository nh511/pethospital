package com.pethospital.triage;

public final class InvalidTriagePredicateException extends IllegalArgumentException {
    public InvalidTriagePredicateException(String path, String message) {
        super("Invalid triage predicate at " + path + ": " + message);
    }
}
