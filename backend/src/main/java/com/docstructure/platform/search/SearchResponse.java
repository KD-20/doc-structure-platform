package com.docstructure.platform.search;

import java.util.List;

/** Public (unlike the rest of this package's DTOs): GuestAccessController in the guestaccess package also returns this directly from the guest search endpoint. */
public record SearchResponse(List<SearchResultItem> items, long totalElements, int page, int size,
                              boolean semanticQueryProvided, boolean semanticSearchAvailable) {
}
