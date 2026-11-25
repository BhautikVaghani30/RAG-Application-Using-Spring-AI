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

@Table("repositories")
@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class Repository {
  @Id private UUID id;

  @Column("user_id")
  private UUID userId;

  @Column("github_repo_id")
  private Long githubRepoId;

  private String owner;

  private String name;

  @Column("full_name")
  private String fullName;

  @Column("is_private")
  private boolean isPrivate;

  @Column("default_branch")
  private String defaultBranch;

  private String language;

  @Column("html_url")
  private String htmlUrl;

  private String description;

  @Column("index_status")
  @Builder.Default
  private IndexStatus indexStatus = IndexStatus.PENDING;

  @Column("indexed_at")
  private Instant indexedAt;

  @Column("chunk_count")
  @Builder.Default
  private int chunkCount = 0;

  @Column("files_total")
  @Builder.Default
  private int filesTotal = 0;

  @Column("files_processed")
  @Builder.Default
  private int filesProcessed = 0;

  @Column("error_message")
  private String errorMessage;

  @Column("created_at")
  private Instant createdAt;

  @Column("updated_at")
  private Instant updatedAt;
}
