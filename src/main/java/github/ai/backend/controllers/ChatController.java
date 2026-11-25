package github.ai.backend.controllers;

import java.util.UUID;

import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import github.ai.backend.dto.ChatMessageRequest;
import github.ai.backend.dto.ChatMessageResponse;
import github.ai.backend.dto.ChatSessionResponse;
import github.ai.backend.dto.CreateChatSessionRequest;
import github.ai.backend.security.AppUserPrincipal;
import github.ai.backend.services.ChatService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/chat")
@RequiredArgsConstructor
public class ChatController {
  private final ChatService chatService;

  @PostMapping("/sessions")
  public Mono<ChatSessionResponse> createSession(
      @AuthenticationPrincipal AppUserPrincipal principal,
      @Valid @RequestBody CreateChatSessionRequest request) {
    return chatService.createSession(principal.getId(), request);
  }

  @GetMapping("/sessions")
  public Flux<ChatSessionResponse> listSessions(
      @AuthenticationPrincipal AppUserPrincipal principal, @RequestParam UUID repositoryId) {
    return chatService.listSessions(principal.getId(), repositoryId);
  }

  @GetMapping("/sessions/{id}")
  public Flux<ChatMessageResponse> getMessages(
      @AuthenticationPrincipal AppUserPrincipal principal, @PathVariable UUID id) {
    return chatService.getMessages(principal.getId(), id);
  }

  @PostMapping(value = "/sessions/{id}/messages", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  public Flux<ServerSentEvent<Object>> sendMessage(
      @AuthenticationPrincipal AppUserPrincipal principal,
      @PathVariable UUID id,
      @Valid @RequestBody ChatMessageRequest request) {
    return chatService.streamReply(principal.getId(), id, request.content());
  }
}
