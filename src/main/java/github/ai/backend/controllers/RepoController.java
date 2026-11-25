package github.ai.backend.controllers;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import github.ai.backend.dto.IndexStatusResponse;
import github.ai.backend.dto.RepositoryResponse;
import github.ai.backend.security.AppUserPrincipal;
import github.ai.backend.services.RepoService;
import github.ai.backend.services.indexing.IndexingService;
import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/repos")
@RequiredArgsConstructor
public class RepoController {
  private final RepoService repoService;
  private final IndexingService indexingService;

  @GetMapping
  public Flux<RepositoryResponse> list(
      @AuthenticationPrincipal AppUserPrincipal principal,
      @RequestParam(name = "refresh", defaultValue = "true") boolean refresh) {
    UUID userId = principal.getId();
    return refresh ? repoService.syncAndListRepos(userId) : repoService.listStored(userId);
  }

  @GetMapping("/{id}")
  public Mono<RepositoryResponse> get(
      @AuthenticationPrincipal AppUserPrincipal principal, @PathVariable UUID id) {
    return repoService.requireOwned(id, principal.getId()).map(repoService::toResponse);
  }

  @PostMapping("/{id}/index")
  public Mono<ResponseEntity<RepositoryResponse>> index(
      @AuthenticationPrincipal AppUserPrincipal principal, @PathVariable UUID id) {
    UUID userId = principal.getId();
    return indexingService
        .startIndexing(id, userId)
        .flatMap(repo ->
            indexingService
                .queueIndexing(id, userId)
                .thenReturn(
                    ResponseEntity.status(HttpStatus.ACCEPTED).body(repoService.toResponse(repo))));
  }

  @GetMapping("/{id}/status")
  public Mono<IndexStatusResponse> status(
      @AuthenticationPrincipal AppUserPrincipal principal, @PathVariable UUID id) {
    return repoService.status(id, principal.getId());
  }
}
