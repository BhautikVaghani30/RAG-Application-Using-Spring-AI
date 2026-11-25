package github.ai.backend.services.ai;

import java.time.Instant;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Component;

import github.ai.backend.dto.ChatMessageResponse;
import github.ai.backend.dto.CitationDto;
import github.ai.backend.entity.ChatMessage;
import github.ai.backend.entity.MessageRole;
import github.ai.backend.repository.ChatMessageRepository;
import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Component
@RequiredArgsConstructor
public class ChatStreamHandler {

  private final ChatModel chatModel;
  private final ChatMessageRepository chatMessageRepository;
  private final CitationMapper citationMapper;

  public Flux<ServerSentEvent<Object>> stream(
      UUID sessionId,
      ChatMessageResponse savedUserMessage,
      List<CitationDto> citations,
      String systemPrompt,
      String userPrompt) {
    StringBuilder fullReply = new StringBuilder();
    Flux<ServerSentEvent<Object>> tokens =
        ChatClient.builder(chatModel)
            .build()
            .prompt()
            .system(systemPrompt)
            .user(userPrompt)
            .stream()
            .content()
            .timeout(Duration.ofMillis(RagSettings.STREAM_TIMEOUT_MS))
            .doOnNext(fullReply::append)
            .map(token -> event("token", token));

    return Flux.just(event("user_message", savedUserMessage))
        .concatWith(tokens)
        .concatWith(
            Flux.defer(() ->
                saveAssistantReply(sessionId, fullReply.toString(), citations)
                    .flatMapMany(message ->
                        Flux.just(
                            event("assistant_message", toMessageResponse(message)),
                            event("done", "[DONE]")))));
  }

  private Mono<ChatMessage> saveAssistantReply(
      UUID sessionId, String reply, List<CitationDto> citations) {
    ChatMessage assistant =
        ChatMessage.builder()
            .sessionId(sessionId)
            .role(MessageRole.ASSISTANT)
            .content(reply)
            .citations(citationMapper.toJson(citations))
            .createdAt(Instant.now())
            .build();
    return chatMessageRepository.save(assistant);
  }

  private ChatMessageResponse toMessageResponse(ChatMessage message) {
    return new ChatMessageResponse(
        message.getId(),
        message.getRole(),
        message.getContent(),
        citationMapper.fromJson(message.getCitations()),
        message.getCreatedAt());
  }

  private static ServerSentEvent<Object> event(String name, Object data) {
    return ServerSentEvent.builder(data).event(name).build();
  }
}
