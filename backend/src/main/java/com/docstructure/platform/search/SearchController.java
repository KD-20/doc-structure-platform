package com.docstructure.platform.search;

import com.docstructure.platform.common.ApiExceptions;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/tenants/{tenantId}/search")
public class SearchController {

    private final SearchService searchService;
    private final ObjectMapper objectMapper;

    public SearchController(SearchService searchService, ObjectMapper objectMapper) {
        this.searchService = searchService;
        this.objectMapper = objectMapper;
    }

    @PreAuthorize("@tenantAccess.isCurrentTenant(#tenantId) and hasRole('VIEWER')")
    @GetMapping
    public SearchResponse search(@PathVariable UUID tenantId,
                                  @RequestParam(required = false) String q,
                                  @RequestParam(required = false) String docType,
                                  @RequestParam(required = false) String filters,
                                  @RequestParam(required = false) String semanticQuery,
                                  @RequestParam(defaultValue = "0") int page,
                                  @RequestParam(defaultValue = "20") int size) {
        List<SearchFilter> parsedFilters = parseFilters(filters);
        return searchService.search(tenantId, docType, q, parsedFilters, semanticQuery, page, size);
    }

    @PreAuthorize("@tenantAccess.isCurrentTenant(#tenantId) and hasRole('VIEWER')")
    @GetMapping("/doc-types")
    public List<String> docTypes(@PathVariable UUID tenantId) {
        return searchService.docTypes(tenantId);
    }

    @PreAuthorize("@tenantAccess.isCurrentTenant(#tenantId) and hasRole('VIEWER')")
    @GetMapping("/fields")
    public Page<String> fields(@PathVariable UUID tenantId, @RequestParam(required = false) String docType,
                                @RequestParam(required = false) String q, Pageable pageable) {
        return searchService.fieldNames(tenantId, docType, q, pageable);
    }

    private List<SearchFilter> parseFilters(String filters) {
        if (filters == null || filters.isBlank()) {
            return List.of();
        }
        try {
            return List.of(objectMapper.readValue(filters, SearchFilter[].class));
        } catch (Exception e) {
            throw new ApiExceptions.ValidationException("Invalid filters JSON: " + e.getMessage());
        }
    }
}
