package github.ai.backend.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.ReactiveAuthenticationManager;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.oauth2.client.authentication.OAuth2LoginReactiveAuthenticationManager;
import org.springframework.security.oauth2.client.endpoint.WebClientReactiveAuthorizationCodeTokenResponseClient;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.authentication.RedirectServerAuthenticationFailureHandler;
import org.springframework.security.web.server.authentication.RedirectServerAuthenticationSuccessHandler;

import github.ai.backend.security.GithubOAuth2UserService;
import lombok.RequiredArgsConstructor;

@Configuration
@RequiredArgsConstructor
public class SecurityConfig {
  private final GithubOAuth2UserService gitHubOAuth2UserService;

  @Bean
  ReactiveAuthenticationManager githubOAuth2LoginAuthenticationManager() {
    return new OAuth2LoginReactiveAuthenticationManager(
        new WebClientReactiveAuthorizationCodeTokenResponseClient(), gitHubOAuth2UserService);
  }

  @Bean
  SecurityWebFilterChain securityWebFilterChain(
      ServerHttpSecurity http,
      ReactiveAuthenticationManager githubOAuth2LoginAuthenticationManager,
      @Value("${app.frontend-url}") String frontendUrl) {
    RedirectServerAuthenticationSuccessHandler successHandler =
        new RedirectServerAuthenticationSuccessHandler(frontendUrl + "/auth/callback");
    RedirectServerAuthenticationFailureHandler failureHandler =
        new RedirectServerAuthenticationFailureHandler(frontendUrl + "/login?error=oauth_failed");

    return http.cors(Customizer.withDefaults())
        .csrf(ServerHttpSecurity.CsrfSpec::disable)
        .authorizeExchange(exchange ->
            exchange
                .pathMatchers("/api/auth/login-url", "/oauth2/**", "/login/oauth2/**", "/error")
                .permitAll()
                .pathMatchers(HttpMethod.OPTIONS, "/**")
                .permitAll()
                .pathMatchers("/api/**")
                .authenticated()
                .anyExchange()
                .permitAll())
        .exceptionHandling(errors ->
            errors.authenticationEntryPoint((exchange, exception) -> {
              exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
              return exchange.getResponse().setComplete();
            }))
        .oauth2Login(oauth2 ->
            oauth2
                .authenticationManager(githubOAuth2LoginAuthenticationManager)
                .authenticationSuccessHandler(successHandler)
                .authenticationFailureHandler(failureHandler))
        .logout(logout ->
            logout
                .logoutUrl("/api/auth/logout")
                .logoutSuccessHandler((exchange, authentication) -> {
                  exchange.getExchange().getResponse().setStatusCode(HttpStatus.NO_CONTENT);
                  return exchange.getExchange().getResponse().setComplete();
                }))
        .build();
  }
}
