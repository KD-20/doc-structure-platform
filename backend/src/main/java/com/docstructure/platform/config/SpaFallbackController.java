package com.docstructure.platform.config;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * The SPA is served as static resources (see docs/DECISIONS.md), but Spring Boot's default
 * static handler only serves requests that match an actual file — a client-side route like
 * /t/{id}/dashboard has no matching file, so without this it 404s on refresh/direct navigation
 * instead of letting React Router render it. Explicit path list (not a wildcard catch-all) so
 * this can't accidentally swallow /api/** 404s or real static asset misses.
 */
@Controller
public class SpaFallbackController {

    @GetMapping({"/", "/login", "/register", "/tenants", "/t/**", "/guest/**", "/try"})
    public String forwardToIndex() {
        return "forward:/index.html";
    }
}
