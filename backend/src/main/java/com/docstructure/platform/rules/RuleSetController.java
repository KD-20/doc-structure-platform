package com.docstructure.platform.rules;

import com.docstructure.platform.auth.AppPrincipal;
import com.docstructure.platform.extraction.BulkReextractionService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/tenants/{tenantId}/rule-sets")
public class RuleSetController {

    private final RuleSetService ruleSetService;
    private final BulkReextractionService bulkReextractionService;

    public RuleSetController(RuleSetService ruleSetService, BulkReextractionService bulkReextractionService) {
        this.ruleSetService = ruleSetService;
        this.bulkReextractionService = bulkReextractionService;
    }

    @PreAuthorize("@tenantAccess.isCurrentTenant(#tenantId) and hasRole('VIEWER')")
    @GetMapping
    public List<RuleSetResponse> list(@PathVariable UUID tenantId) {
        return ruleSetService.list(tenantId).stream()
                .map(rs -> RuleSetResponse.from(rs, ruleSetService.parseDefinition(rs)))
                .toList();
    }

    /** Every doc type that's either tenant-customized or has a built-in default, tagged with which one is actually in effect — what the Rule Sets UI renders. */
    @PreAuthorize("@tenantAccess.isCurrentTenant(#tenantId) and hasRole('VIEWER')")
    @GetMapping("/effective")
    public List<EffectiveRuleSet> effective(@PathVariable UUID tenantId) {
        return ruleSetService.listEffective(tenantId);
    }

    @PreAuthorize("@tenantAccess.isCurrentTenant(#tenantId) and hasRole('VIEWER')")
    @GetMapping("/{docType}/active")
    public RuleSetResponse getActive(@PathVariable UUID tenantId, @PathVariable String docType) {
        ExtractionRuleSet rs = ruleSetService.getActive(tenantId, docType);
        return RuleSetResponse.from(rs, ruleSetService.parseDefinition(rs));
    }

    @PreAuthorize("@tenantAccess.isCurrentTenant(#tenantId) and hasRole('VIEWER')")
    @GetMapping("/{docType}/versions/{version}")
    public RuleSetResponse getVersion(@PathVariable UUID tenantId, @PathVariable String docType,
                                       @PathVariable int version) {
        ExtractionRuleSet rs = ruleSetService.getVersion(tenantId, docType, version);
        return RuleSetResponse.from(rs, ruleSetService.parseDefinition(rs));
    }

    @PreAuthorize("@tenantAccess.isCurrentTenant(#tenantId) and hasRole('ADMIN')")
    @PutMapping("/{docType}")
    public ResponseEntity<RuleSetResponse> createVersion(@PathVariable UUID tenantId, @PathVariable String docType,
                                                           @Valid @RequestBody CreateRuleSetVersionRequest request,
                                                           @AuthenticationPrincipal AppPrincipal principal) {
        ExtractionRuleSet rs = ruleSetService.createVersion(tenantId, docType, request.definition(), principal.userId());
        bulkReextractionService.reextractByDocType(tenantId, docType, principal.userId());
        return ResponseEntity.status(HttpStatus.CREATED).body(RuleSetResponse.from(rs, request.definition()));
    }

    @PreAuthorize("@tenantAccess.isCurrentTenant(#tenantId) and hasRole('ADMIN')")
    @PostMapping("/{docType}/versions/{version}/activate")
    public RuleSetResponse activate(@PathVariable UUID tenantId, @PathVariable String docType,
                                     @PathVariable int version, @AuthenticationPrincipal AppPrincipal principal) {
        ExtractionRuleSet rs = ruleSetService.activateVersion(tenantId, docType, version);
        bulkReextractionService.reextractByDocType(tenantId, docType, principal.userId());
        return RuleSetResponse.from(rs, ruleSetService.parseDefinition(rs));
    }

    /**
     * Manual counterpart to the automatic re-extraction createVersion/activate already trigger
     * — for re-syncing existing documents without changing the rule set itself (e.g. after
     * fixing something unrelated, or just to confirm the current definition still behaves as
     * expected against what's already uploaded).
     */
    @PreAuthorize("@tenantAccess.isCurrentTenant(#tenantId) and hasRole('EDITOR')")
    @PostMapping("/{docType}/reextract")
    public BulkReextractionService.Result reextract(@PathVariable UUID tenantId, @PathVariable String docType,
                                                      @AuthenticationPrincipal AppPrincipal principal) {
        return bulkReextractionService.reextractByDocType(tenantId, docType, principal.userId());
    }

    @PreAuthorize("@tenantAccess.isCurrentTenant(#tenantId) and hasRole('EDITOR')")
    @PostMapping("/preview")
    public PreviewResponse preview(@PathVariable UUID tenantId, @Valid @RequestBody PreviewRequest request) {
        return new PreviewResponse(ruleSetService.preview(request.definition(), request.sampleText()));
    }
}
