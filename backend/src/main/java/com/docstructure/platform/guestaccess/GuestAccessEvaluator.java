package com.docstructure.platform.guestaccess;

import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.util.UUID;

/** Referenced from @PreAuthorize as @guestAccessEvaluator.canAccess(#documentId) on top of the plain hasRole('GUEST') check — a valid guest token only grants access to the specific documents its link was scoped to at creation time. */
@Component("guestAccessEvaluator")
public class GuestAccessEvaluator {

    public boolean canAccess(UUID documentId) {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof GuestPrincipal principal)) {
            return false;
        }
        return principal.documentIds().contains(documentId);
    }
}
