package com.mfplatform.mfplatform.scheme;

public class SchemeNotFoundException extends RuntimeException {
    public SchemeNotFoundException(Long id) {
        super("Scheme not found: " + id);
    }
}
