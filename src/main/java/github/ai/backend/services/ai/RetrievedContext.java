package github.ai.backend.services.ai;

import java.util.List;

import github.ai.backend.dto.CitationDto;

public record RetrievedContext(List<CitationDto> citations, String contextText) {}
