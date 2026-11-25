package github.ai.backend.services.indexing;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import github.ai.backend.entity.IndexStatus;
import github.ai.backend.entity.Repository;
import github.ai.backend.exceptions.BadRequestException;
import github.ai.backend.exceptions.NotFoundException;
import github.ai.backend.repository.RepositoryRepository;
import github.ai.backend.services.UserService;
import github.ai.backend.services.ai.RagSettings;
import github.ai.backend.services.github.GitHubRateLimiter;
import github.ai.backend.services.github.GithubApiClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;

@Service
@RequiredArgsConstructor
@Slf4j
public class IndexingService {
  private static final int VECTOR_BATCH_SIZE = 32;
  private static final int PROGRESS_EVERY_N_FILES = 5;
  private static final int INDEXING_CONCURRENCY = 4;

  private final RepositoryRepository repositoryRepository;
  private final UserService userService;
  private final GithubApiClient gitHubApiClient;
  private final CodeFileFilter fileFilter;
  private final CodeChunker codeChunker;
  private final GitHubRateLimiter rateLimiter;
  private final VectorStore vectorStore;
  private final Sinks.Many<IndexJob> indexJobs =
      Sinks.many().unicast().onBackpressureBuffer(new ArrayBlockingQueue<>(50));
  private reactor.core.Disposable indexWorkers;

  @Value("${app.indexing.max-file-bytes:102400}")
  private long maxFileBytes;

  @PostConstruct
  void startIndexWorkers() {
    indexWorkers =
        indexJobs
            .asFlux()
            .flatMap(
                job ->
                    indexAsync(job.repositoryId(), job.userId())
                        .doOnError(error ->
                            log.error(
                                "Indexing failed for repository {}", job.repositoryId(), error))
                        .onErrorComplete(),
                INDEXING_CONCURRENCY)
            .subscribe();
  }

  @PreDestroy
  void stopIndexWorkers() {
    if (indexWorkers != null) {
      indexWorkers.dispose();
    }
  }

  @Transactional
  public Mono<Repository> startIndexing(UUID repoId, UUID userId) {
    return repositoryRepository
        .findByIdAndUserId(repoId, userId)
        .switchIfEmpty(Mono.error(new NotFoundException("Repository not found")))
        .flatMap(repo -> {
          if (repo.getIndexStatus() == IndexStatus.INDEXING) {
            return Mono.error(new BadRequestException("Repository is already being indexed"));
          }
          repo.setIndexStatus(IndexStatus.INDEXING);
          repo.setFilesProcessed(0);
          repo.setFilesTotal(0);
          repo.setChunkCount(0);
          repo.setErrorMessage(null);
          repo.setUpdatedAt(Instant.now());
          return repositoryRepository.save(repo);
        });
  }

  public Mono<Void> queueIndexing(UUID repoId, UUID userId) {
    return Mono.<Void>fromRunnable(
            () -> {
              Sinks.EmitResult result = indexJobs.tryEmitNext(new IndexJob(repoId, userId));
              if (result.isFailure()) {
                throw new IllegalStateException("Indexing queue is full or unavailable: " + result);
              }
            })
        .onErrorResume(
            error -> markFailed(repoId, error.getMessage()).then(Mono.<Void>error(error)));
  }

  public Mono<Void> indexAsync(UUID repoId, UUID userId) {
    return repositoryRepository
        .findById(repoId)
        .switchIfEmpty(Mono.error(new NotFoundException("Repository not found")))
        .flatMap(repo ->
            userService
                .requiredById(userId)
                .map(userService::decryptAccessToken)
                .flatMap(token ->
                    deleteExistingVectors(repoId.toString())
                        .then(
                            gitHubApiClient.getRepoTree(
                                token, repo.getOwner(), repo.getName(), repo.getDefaultBranch()))
                        .defaultIfEmpty(Map.of())
                        .flatMap(tree -> indexFiles(repo, token, tree))))
        .onErrorResume(error -> markFailed(repoId, error.getMessage()).then(Mono.error(error)));
  }

  private Mono<Void> indexFiles(Repository repo, String token, Map<String, Object> tree) {
    return Mono.fromCallable(() -> listIndexableFiles(tree))
        .subscribeOn(Schedulers.parallel())
        .flatMap(filePaths -> {
          AtomicInteger processed = new AtomicInteger();
          AtomicInteger totalChunks = new AtomicInteger();

          return updateProgress(repo.getId(), filePaths.size(), 0, 0, IndexStatus.INDEXING, null)
              .thenMany(
                  Flux.fromIterable(filePaths)
                      .concatMap(path ->
                          rateLimiter
                              .pause()
                              .then(readFile(repo, token, path))
                              .flatMap(chunks -> saveVectors(chunks).thenReturn(chunks.size()))
                              .flatMap(chunkCount -> {
                                int fileCount = processed.incrementAndGet();
                                int chunks = totalChunks.addAndGet(chunkCount);
                                if (fileCount % PROGRESS_EVERY_N_FILES == 0
                                    || fileCount == filePaths.size()) {
                                  return updateProgress(
                                      repo.getId(),
                                      filePaths.size(),
                                      fileCount,
                                      chunks,
                                      IndexStatus.INDEXING,
                                      null);
                                }
                                return Mono.empty();
                              })))
              .then(
                  markReady(
                      repo.getId(),
                      filePaths.size(),
                      processed.get(),
                      totalChunks.get(),
                      repo.getFullName()));
        });
  }

  private Mono<List<Document>> readFile(Repository repo, String token, String path) {
    return gitHubApiClient
        .getFileContent(token, repo.getOwner(), repo.getName(), path)
        .defaultIfEmpty("")
        .flatMap(content ->
            Mono.fromCallable(() -> codeChunker.chunkFile(repo.getId().toString(), path, content))
                .subscribeOn(Schedulers.parallel()))
        .onErrorResume(error -> {
          log.warn("Skipping file {} in {}: {}", path, repo.getFullName(), error.getMessage());
          return Mono.just(List.of());
        });
  }

  private Mono<Void> saveVectors(List<Document> documents) {
    return Flux.fromIterable(documents)
        .buffer(VECTOR_BATCH_SIZE)
        .concatMap(batch ->
            Mono.fromRunnable(() -> vectorStore.add(batch))
                .subscribeOn(Schedulers.boundedElastic()))
        .then();
  }

  @SuppressWarnings("unchecked")
  private List<String> listIndexableFiles(Map<String, Object> tree) {
    Object rawTree = tree.get("tree");
    if (!(rawTree instanceof List<?> entries)) {
      return List.of();
    }
    return entries.stream()
        .filter(Map.class::isInstance)
        .map(entry -> (Map<String, Object>) entry)
        .filter(entry -> "blob".equals(String.valueOf(entry.get("type"))))
        .filter(entry ->
            fileFilter.isEligible(
                String.valueOf(entry.get("path")),
                entry.get("size") instanceof Number size ? size.longValue() : 0L,
                maxFileBytes))
        .map(entry -> String.valueOf(entry.get("path")))
        .toList();
  }

  private Mono<Void> deleteExistingVectors(String repoId) {
    return Mono.fromRunnable(
            () -> {
              var filter =
                  new FilterExpressionBuilder().eq(RagSettings.METADATA_REPO_ID, repoId).build();
              vectorStore.delete(filter);
            })
        .subscribeOn(Schedulers.boundedElastic())
        .then();
  }

  @Transactional
  protected Mono<Void> updateProgress(
      UUID repoId, int total, int processed, int chunks, IndexStatus status, String error) {
    return repositoryRepository
        .findById(repoId)
        .switchIfEmpty(Mono.error(new NotFoundException("Repository not found")))
        .flatMap(repo -> {
          repo.setFilesTotal(total);
          repo.setFilesProcessed(processed);
          repo.setChunkCount(chunks);
          repo.setIndexStatus(status);
          repo.setErrorMessage(error);
          repo.setUpdatedAt(Instant.now());
          return repositoryRepository.save(repo);
        })
        .then();
  }

  @Transactional
  protected Mono<Void> markReady(
      UUID repoId, int totalFiles, int processedFiles, int totalChunks, String fullName) {
    return repositoryRepository
        .findById(repoId)
        .switchIfEmpty(Mono.error(new NotFoundException("Repository not found")))
        .flatMap(repo -> {
          repo.setIndexStatus(IndexStatus.READY);
          repo.setFilesTotal(totalFiles);
          repo.setFilesProcessed(processedFiles);
          repo.setChunkCount(totalChunks);
          repo.setIndexedAt(Instant.now());
          repo.setErrorMessage(null);
          repo.setUpdatedAt(Instant.now());
          return repositoryRepository.save(repo);
        })
        .doOnSuccess(ignored ->
            log.info("Indexed {} files ({} chunks) for {}", processedFiles, totalChunks, fullName))
        .then();
  }

  @Transactional
  protected Mono<Void> markFailed(UUID repoId, String message) {
    return repositoryRepository
        .findById(repoId)
        .flatMap(repo -> {
          repo.setIndexStatus(IndexStatus.FAILED);
          repo.setErrorMessage(
              message != null && message.length() > 2000 ? message.substring(0, 2000) : message);
          repo.setUpdatedAt(Instant.now());
          return repositoryRepository.save(repo);
        })
        .then();
  }

  private record IndexJob(UUID repositoryId, UUID userId) {}
}
