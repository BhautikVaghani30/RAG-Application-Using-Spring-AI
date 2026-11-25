package github.ai.backend.entity;

import java.time.Instant;
import java.util.UUID;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Table("users")
@Builder
public class User {

  @Id private UUID id;

  @Column("github_id")
  private Long githubId;

  @Column("github_username")
  private String githubUsername;

  @Column("display_name")
  private String displayName;

  @Column("avatar_url")
  private String avatarUrl;

  @Column("access_token")
  private String accessToken;

  @Column("token_scopes")
  private String tokenScopes;

  @Column("created_at")
  private Instant createdAt;
}
