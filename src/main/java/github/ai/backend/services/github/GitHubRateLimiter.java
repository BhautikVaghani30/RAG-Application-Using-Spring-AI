package github.ai.backend.services.github;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import reactor.core.publisher.Mono;

/** Simple delay helper to stay under GitHub API secondary rate limits during indexing. */
@Component
public class GitHubRateLimiter {

  private final long delayMs;

  public GitHubRateLimiter(@Value("${app.github.api-delay-ms:50}") long delayMs) {
    this.delayMs = Math.max(0, delayMs);
  }

  public Mono<Void> pause() {
    if (delayMs <= 0) {
      return Mono.empty();
    }
    return Mono.delay(Duration.ofMillis(delayMs)).then();
  }
}
