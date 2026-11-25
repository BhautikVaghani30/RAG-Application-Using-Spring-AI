package github.ai.backend.repository;

import java.util.UUID;

import org.springframework.data.repository.reactive.ReactiveCrudRepository;

import github.ai.backend.entity.ChatSession;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface ChatSessionRepository extends ReactiveCrudRepository<ChatSession, UUID> {
  Flux<ChatSession> findByUserIdAndRepositoryIdOrderByCreatedAtDesc(UUID userId, UUID repositoryId);

  Mono<ChatSession> findByIdAndUserId(UUID id, UUID userId);
}
