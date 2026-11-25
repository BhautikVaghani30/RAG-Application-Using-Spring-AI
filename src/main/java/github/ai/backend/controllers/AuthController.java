package github.ai.backend.controllers;

import java.util.Map;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import github.ai.backend.dto.UserResponse;
import github.ai.backend.security.AppUserPrincipal;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
  @GetMapping("/login-url")
  public Map<String, String> loginUrl() {
    return Map.of("url", "/oauth2/authorization/github");
  }

  @GetMapping("/me")
  public UserResponse me(@AuthenticationPrincipal AppUserPrincipal principal) {
    var user = principal.getUser();
    return new UserResponse(
        user.getId(),
        user.getGithubId(),
        user.getGithubUsername(),
        user.getDisplayName(),
        user.getAvatarUrl());
  }
}
