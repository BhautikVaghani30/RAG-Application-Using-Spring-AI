package github.ai.backend.services;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;

import github.ai.backend.dto.IndexStatusResponse;
import github.ai.backend.dto.RepositoryResponse;
import github.ai.backend.entity.Repository;
import github.ai.backend.exceptions.NotFoundException;
import github.ai.backend.repository.RepositoryRepository;
import github.ai.backend.services.github.GithubApiClient;
import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Service
@RequiredArgsConstructor
public class RepoService {
  private final RepositoryRepository repositoryRepository;
  private final UserService userService;
  private final GithubApiClient gitHubApiClient;

  public Flux<RepositoryResponse> syncAndListRepos(UUID userId) {
    return userService
        .requiredById(userId)
        .map(userService::decryptAccessToken)
        .flatMapMany(gitHubApiClient::listUserRepos)
        .concatMap(remote -> upsertRepository(userId, remote))
        .sort((left, right) -> left.fullName().compareToIgnoreCase(right.fullName()));
  }

  private Mono<RepositoryResponse> upsertRepository(UUID userId, Map<String, Object> remote) {
    Long githubRepoId = toLong(remote.get("id"));
    return repositoryRepository
        .findByUserIdAndGithubRepoId(userId, githubRepoId)
        .defaultIfEmpty(
            Repository.builder()
                .userId(userId)
                .githubRepoId(githubRepoId)
                .createdAt(Instant.now())
                .build())
        .map(repo -> updateRepository(repo, remote))
        .flatMap(repositoryRepository::save)
        .map(this::toResponse);
  }

  private Repository updateRepository(Repository repo, Map<String, Object> remote) {
    String fullName = String.valueOf(remote.get("full_name"));
    String[] parts = fullName.split("/", 2);
    repo.setOwner(parts[0]);
    repo.setName(parts.length > 1 ? parts[1] : String.valueOf(remote.get("name")));
    repo.setFullName(fullName);
    repo.setPrivate(Boolean.TRUE.equals(remote.get("private")));
    repo.setDefaultBranch(
        remote.get("default_branch") == null
            ? "main"
            : String.valueOf(remote.get("default_branch")));
    repo.setLanguage(stringValue(remote.get("language")));
    repo.setHtmlUrl(stringValue(remote.get("html_url")));
    repo.setDescription(stringValue(remote.get("description")));
    repo.setUpdatedAt(Instant.now());
    return repo;
  }

  public Flux<RepositoryResponse> listStored(UUID userId) {
    return repositoryRepository.findByUserIdOrderByFullNameAsc(userId).map(this::toResponse);
  }

  public Mono<Repository> requireOwned(UUID repoId, UUID userId) {
    return repositoryRepository
        .findByIdAndUserId(repoId, userId)
        .switchIfEmpty(Mono.error(new NotFoundException("Repository not found")));
  }

  public Mono<IndexStatusResponse> status(UUID repoId, UUID userId) {
    return requireOwned(repoId, userId)
        .map(repo ->
            new IndexStatusResponse(
                repo.getId(),
                repo.getIndexStatus(),
                repo.getFilesTotal(),
                repo.getFilesProcessed(),
                repo.getChunkCount(),
                repo.getIndexedAt(),
                repo.getErrorMessage()));
  }

  public RepositoryResponse toResponse(Repository repo) {
    return new RepositoryResponse(
        repo.getId(),
        repo.getGithubRepoId(),
        repo.getOwner(),
        repo.getName(),
        repo.getFullName(),
        repo.isPrivate(),
        repo.getDefaultBranch(),
        repo.getLanguage(),
        repo.getHtmlUrl(),
        repo.getDescription(),
        repo.getIndexStatus(),
        repo.getIndexedAt(),
        repo.getChunkCount(),
        repo.getFilesTotal(),
        repo.getFilesProcessed(),
        repo.getErrorMessage());
  }

  private static String stringValue(Object value) {
    return value == null ? null : String.valueOf(value);
  }

  private static Long toLong(Object value) {
    if (value instanceof Number number) {
      return number.longValue();
    }
    return Long.parseLong(String.valueOf(value));
  }
}
