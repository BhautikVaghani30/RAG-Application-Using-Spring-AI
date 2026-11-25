package github.ai.backend.repository;

import java.util.UUID;

import org.springframework.data.repository.reactive.ReactiveCrudRepository;

import github.ai.backend.entity.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface RepositoryRepository extends ReactiveCrudRepository<Repository, UUID> {
  Flux<Repository> findByUserIdOrderByFullNameAsc(UUID userId);

  Mono<Repository> findByIdAndUserId(UUID id, UUID userId);

  Mono<Repository> findByUserIdAndGithubRepoId(UUID userId, Long githubRepoId);
}
