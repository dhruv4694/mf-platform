package com.mfplatform.mfplatform.security;

import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

@Component
public class CurrentUserResolver {

    public ActorContext resolve(Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof ActorContext actor)) {
            throw new IllegalStateException("No authenticated ActorContext present on request");
        }
        return actor;
    }
}
