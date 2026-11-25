package github.ai.backend.services.github;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Service
public class GithubApiClient {

  private static final String API_BASE = "https://api.github.com";
  private static final ParameterizedTypeReference<List<Map<String, Object>>> REPOSITORY_LIST =
      new ParameterizedTypeReference<>() {};
  private static final ParameterizedTypeReference<Map<String, Object>> JSON_OBJECT =
      new ParameterizedTypeReference<>() {};

  private final WebClient.Builder webClientBuilder;

  public GithubApiClient(WebClient.Builder webClientBuilder) {
    this.webClientBuilder = webClientBuilder;
  }

  public Flux<Map<String, Object>> listUserRepos(String accessToken) {
    return listRepositoryPage(accessToken, 1);
  }

  private Flux<Map<String, Object>> listRepositoryPage(String accessToken, int page) {
    if (page > 10) {
      return Flux.empty();
    }

    return client(accessToken)
        .get()
        .uri(uriBuilder ->
            uriBuilder
                .path("/user/repos")
                .queryParam("affiliation", "owner,collaborator,organization_member")
                .queryParam("sort", "updated")
                .queryParam("per_page", 100)
                .queryParam("page", page)
                .build())
        .retrieve()
        .bodyToMono(REPOSITORY_LIST)
        .defaultIfEmpty(List.of())
        .flatMapMany(repositories -> {
          Flux<Map<String, Object>> currentPage = Flux.fromIterable(repositories);
          return repositories.size() < 100
              ? currentPage
              : currentPage.concatWith(listRepositoryPage(accessToken, page + 1));
        });
  }

  public Mono<Map<String, Object>> getRepoTree(
      String accessToken, String owner, String repo, String branch) {
    return client(accessToken)
        .get()
        .uri("/repos/{owner}/{repo}/git/trees/{branch}?recursive=1", owner, repo, branch)
        .retrieve()
        .bodyToMono(JSON_OBJECT);
  }

  public Mono<String> getFileContent(String accessToken, String owner, String repo, String path) {
    return client(accessToken)
        .get()
        .uri("/repos/{owner}/{repo}/contents/{path}", owner, repo, path)
        .retrieve()
        .bodyToMono(JSON_OBJECT)
        .publishOn(reactor.core.scheduler.Schedulers.parallel())
        .map(GithubApiClient::decodeFileContent);
  }

  private WebClient client(String accessToken) {
    return webClientBuilder
        .clone()
        .baseUrl(API_BASE)
        .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
        .defaultHeader(HttpHeaders.ACCEPT, "application/vnd.github+json")
        .defaultHeader("X-GitHub-Api-Version", "2022-11-28")
        .defaultHeader(HttpHeaders.USER_AGENT, "GitHubAiBackend")
        .build();
  }

  private static String decodeFileContent(Map<String, Object> body) {
    Object content = body.get("content");
    if (content == null) {
      return "";
    }
    if ("base64".equals(String.valueOf(body.get("encoding")))) {
      String encoded = String.valueOf(content).replaceAll("\\s", "");
      return new String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8);
    }
    return String.valueOf(content);
  }
}
