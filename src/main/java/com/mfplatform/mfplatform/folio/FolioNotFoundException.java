package com.mfplatform.mfplatform.folio;

public class FolioNotFoundException extends RuntimeException {
    public FolioNotFoundException(Long id) {
        super("Folio not found: " + id);
    }
}
