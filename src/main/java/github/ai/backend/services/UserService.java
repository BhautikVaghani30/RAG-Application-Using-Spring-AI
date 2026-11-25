package github.ai.backend.services;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import org.springframework.security.crypto.encrypt.TextEncryptor;
import org.springframework.stereotype.Service;

import github.ai.backend.entity.User;
import github.ai.backend.exceptions.NotFoundException;
import github.ai.backend.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Mono;

@Service
@RequiredArgsConstructor
public class UserService {
  private final UserRepository userRepository;
  private final TextEncryptor tokenEncryptor;

  public Mono<User> upsertFromGitHub(
      Map<String, Object> attributes, String accessToken, String scopes) {
    Long githubId = toLong(attributes.get("id"));
    String login = String.valueOf(attributes.get("login"));
    String displayName =
        attributes.get("name") == null ? login : String.valueOf(attributes.get("name"));
    String avatarUrl =
        attributes.get("avatar_url") == null ? null : String.valueOf(attributes.get("avatar_url"));

    return userRepository
        .findByGithubId(githubId)
        .defaultIfEmpty(User.builder().createdAt(Instant.now()).build())
        .map(user -> {
          user.setGithubId(githubId);
          user.setGithubUsername(login);
          user.setDisplayName(displayName);
          user.setAvatarUrl(avatarUrl);
          user.setAccessToken(tokenEncryptor.encrypt(accessToken));
          user.setTokenScopes(scopes);
          return user;
        })
        .flatMap(userRepository::save);
  }

  public Mono<User> requiredById(UUID id) {
    return userRepository
        .findById(id)
        .switchIfEmpty(Mono.error(new NotFoundException("User not found")));
  }

  public String decryptAccessToken(User user) {
    return tokenEncryptor.decrypt(user.getAccessToken());
  }

  private static Long toLong(Object value) {
    if (value instanceof Number number) {
      return number.longValue();
    }
    return Long.parseLong(String.valueOf(value));
  }
}
