package com.mfplatform.mfplatform.sip;

public class SipMandateNotFoundException extends RuntimeException {
    public SipMandateNotFoundException(Long id) {
        super("SIP mandate not found: " + id);
    }
}
