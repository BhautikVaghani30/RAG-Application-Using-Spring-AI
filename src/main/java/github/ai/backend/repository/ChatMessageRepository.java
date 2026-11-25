package github.ai.backend.repository;

import java.util.UUID;

import org.springframework.data.repository.reactive.ReactiveCrudRepository;

import github.ai.backend.entity.ChatMessage;
import reactor.core.publisher.Flux;

public interface ChatMessageRepository extends ReactiveCrudRepository<ChatMessage, UUID> {
  Flux<ChatMessage> findBySessionIdOrderByCreatedAtAsc(UUID sessionId);
}
