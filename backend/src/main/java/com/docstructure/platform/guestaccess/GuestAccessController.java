package com.docstructure.platform.guestaccess;

import com.docstructure.platform.search.SearchResponse;
import com.docstructure.platform.search.SearchService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/guest")
public class GuestAccessController {

    private final GuestAccessService guestAccessService;
    private final SearchService searchService;

    public GuestAccessController(GuestAccessService guestAccessService, SearchService searchService) {
        this.guestAccessService = guestAccessService;
        this.searchService = searchService;
    }

    @PreAuthorize("hasRole('GUEST') and @guestAccessEvaluator.canAccess(#documentId)")
    @GetMapping("/documents/{documentId}")
    public GuestDocumentResponse getDocument(@PathVariable UUID documentId, @AuthenticationPrincipal GuestPrincipal principal) {
        return guestAccessService.getDocument(principal.tenantId(), documentId);
    }

    @PreAuthorize("hasRole('GUEST')")
    @GetMapping("/search")
    public SearchResponse search(@RequestParam(required = false) String q,
                                  @RequestParam(defaultValue = "0") int page,
                                  @RequestParam(defaultValue = "20") int size,
                                  @AuthenticationPrincipal GuestPrincipal principal) {
        return searchService.searchWithinDocuments(principal.tenantId(), q, principal.documentIds(), page, size);
    }
}
