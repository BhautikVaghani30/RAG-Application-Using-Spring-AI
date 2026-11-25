package github.ai.backend.security;

import org.springframework.security.oauth2.client.userinfo.DefaultReactiveOAuth2UserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.client.userinfo.ReactiveOAuth2UserService;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Service;

import github.ai.backend.services.UserService;
import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Mono;

@Service
@RequiredArgsConstructor
public class GithubOAuth2UserService
    implements ReactiveOAuth2UserService<OAuth2UserRequest, OAuth2User> {
  private final UserService userService;
  private final DefaultReactiveOAuth2UserService delegate = new DefaultReactiveOAuth2UserService();

  @Override
  public Mono<OAuth2User> loadUser(OAuth2UserRequest userRequest) {
    return delegate
        .loadUser(userRequest)
        .flatMap(githubUser -> {
          String accessToken = userRequest.getAccessToken().getTokenValue();
          String scopes =
              userRequest.getAccessToken().getScopes() == null
                  ? "read:user,repo"
                  : String.join(",", userRequest.getAccessToken().getScopes());
          return userService
              .upsertFromGitHub(githubUser.getAttributes(), accessToken, scopes)
              .map(user -> new AppUserPrincipal(user, githubUser.getAttributes()));
        });
  }
}
