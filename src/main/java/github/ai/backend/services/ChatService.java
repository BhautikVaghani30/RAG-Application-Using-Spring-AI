package github.ai.backend.services;

import java.time.Instant;
import java.util.UUID;

import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;

import github.ai.backend.dto.ChatMessageResponse;
import github.ai.backend.dto.ChatSessionResponse;
import github.ai.backend.dto.CreateChatSessionRequest;
import github.ai.backend.entity.ChatMessage;
import github.ai.backend.entity.ChatSession;
import github.ai.backend.entity.IndexStatus;
import github.ai.backend.entity.MessageRole;
import github.ai.backend.entity.Repository;
import github.ai.backend.exceptions.BadRequestException;
import github.ai.backend.exceptions.NotFoundException;
import github.ai.backend.repository.ChatMessageRepository;
import github.ai.backend.repository.ChatSessionRepository;
import github.ai.backend.services.ai.ChatPromptBuilder;
import github.ai.backend.services.ai.ChatStreamHandler;
import github.ai.backend.services.ai.CitationMapper;
import github.ai.backend.services.ai.CodeContextRetriever;
import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@Service
@RequiredArgsConstructor
public class ChatService {
  private final ChatSessionRepository chatSessionRepository;
  private final ChatMessageRepository chatMessageRepository;
  private final RepoService repoService;
  private final CodeContextRetriever codeContextRetriever;
  private final ChatPromptBuilder chatPromptBuilder;
  private final ChatStreamHandler chatStreamHandler;
  private final CitationMapper citationMapper;

  public Mono<ChatSessionResponse> createSession(UUID userId, CreateChatSessionRequest request) {
    return repoService
        .requireOwned(request.repositoryId(), userId)
        .flatMap(repo -> {
          if (repo.getIndexStatus() != IndexStatus.READY) {
            return Mono.error(
                new BadRequestException("Repository must be indexed before chatting"));
          }
          String title =
              request.title() == null || request.title().isBlank()
                  ? "Chat with " + repo.getFullName()
                  : request.title();
          ChatSession session =
              ChatSession.builder()
                  .userId(userId)
                  .repositoryId(repo.getId())
                  .title(title)
                  .createdAt(Instant.now())
                  .build();
          return chatSessionRepository.save(session).map(this::toSessionResponse);
        });
  }

  public Flux<ChatSessionResponse> listSessions(UUID userId, UUID repositoryId) {
    return repoService
        .requireOwned(repositoryId, userId)
        .flatMapMany(repo ->
            chatSessionRepository.findByUserIdAndRepositoryIdOrderByCreatedAtDesc(
                userId, repositoryId))
        .map(this::toSessionResponse);
  }

  public Flux<ChatMessageResponse> getMessages(UUID userId, UUID sessionId) {
    return requireSession(userId, sessionId)
        .flatMapMany(
            session -> chatMessageRepository.findBySessionIdOrderByCreatedAtAsc(session.getId()))
        .map(this::toMessageResponse);
  }

  public Mono<ChatSession> requireSession(UUID userId, UUID sessionId) {
    return chatSessionRepository
        .findByIdAndUserId(sessionId, userId)
        .switchIfEmpty(Mono.error(new NotFoundException("Chat session not found")));
  }

  public Flux<ServerSentEvent<Object>> streamReply(
      UUID userId, UUID sessionId, String userContent) {
    return requireSession(userId, sessionId)
        .flatMap(session ->
            repoService
                .requireOwned(session.getRepositoryId(), userId)
                .flatMap(repo -> {
                  if (repo.getIndexStatus() != IndexStatus.READY) {
                    return Mono.error(new BadRequestException("Repository is not ready for chat"));
                  }
                  ChatMessage userMessage =
                      ChatMessage.builder()
                          .sessionId(session.getId())
                          .role(MessageRole.USER)
                          .content(userContent)
                          .createdAt(Instant.now())
                          .build();
                  return chatMessageRepository
                      .save(userMessage)
                      .map(saved -> new ChatRequestContext(session, repo, saved));
                }))
        .flatMapMany(context ->
            Mono.fromCallable(
                    () -> codeContextRetriever.retrieve(context.repository().getId(), userContent))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMapMany(retrievedContext ->
                    chatStreamHandler.stream(
                        context.session().getId(),
                        toMessageResponse(context.userMessage()),
                        retrievedContext.citations(),
                        chatPromptBuilder.systemPrompt(context.repository().getFullName()),
                        chatPromptBuilder.userPrompt(
                            retrievedContext.contextText(), userContent))));
  }

  private ChatSessionResponse toSessionResponse(ChatSession session) {
    return new ChatSessionResponse(
        session.getId(), session.getRepositoryId(), session.getTitle(), session.getCreatedAt());
  }

  private ChatMessageResponse toMessageResponse(ChatMessage message) {
    return new ChatMessageResponse(
        message.getId(),
        message.getRole(),
        message.getContent(),
        citationMapper.fromJson(message.getCitations()),
        message.getCreatedAt());
  }

  private record ChatRequestContext(
      ChatSession session, Repository repository, ChatMessage userMessage) {}
}
