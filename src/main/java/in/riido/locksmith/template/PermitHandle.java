package in.riido.locksmith.template;

import in.riido.locksmith.metrics.SemaphoreMetrics;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.redisson.api.RPermitExpirableSemaphore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * An {@link AutoCloseable} handle representing the result of a semaphore permit acquisition
 * attempt.
 *
 * <p>This class enables the try-with-resources pattern for distributed semaphores, ensuring
 * automatic permit release when the handle is closed:
 *
 * <pre>{@code
 * try (PermitHandle handle = semaphoreTemplate.withKey("pool").permits(10).tryAcquire()) {
 *     if (handle.isAcquired()) {
 *         // Use the resource - permit is held
 *         String id = handle.permitId();
 *     }
 * }
 * // Permit is automatically released here
 * }</pre>
 *
 * <p>If the permit was not acquired, {@link #close()} is a safe no-op.
 *
 * @author Garvit Joshi
 * @since 4.0.0
 * @see LocksmithSemaphoreTemplate
 */
public class PermitHandle implements AutoCloseable {

  private static final Logger LOG = LoggerFactory.getLogger(PermitHandle.class);

  private final boolean acquired;
  @Nullable private final String permitId;
  @Nullable private final RPermitExpirableSemaphore semaphore;
  @Nullable private final String fullKey;
  @Nullable private final SemaphoreMetrics semaphoreMetrics;
  private final long heldStartTime;

  private PermitHandle(
      boolean acquired,
      @Nullable String permitId,
      @Nullable RPermitExpirableSemaphore semaphore,
      @Nullable String fullKey,
      @Nullable SemaphoreMetrics semaphoreMetrics) {
    this.acquired = acquired;
    this.permitId = permitId;
    this.semaphore = semaphore;
    this.fullKey = fullKey;
    this.semaphoreMetrics = semaphoreMetrics;
    this.heldStartTime = acquired ? System.currentTimeMillis() : 0;
  }

  /**
   * Creates a handle representing a successful permit acquisition.
   *
   * @param permitId the acquired permit ID
   * @param semaphore the semaphore instance
   * @param fullKey the full Redis key of the semaphore
   * @param semaphoreMetrics the optional semaphore metrics
   * @return a handle with {@code isAcquired() == true}
   */
  @NonNull
  static PermitHandle acquired(
      @NonNull String permitId,
      @NonNull RPermitExpirableSemaphore semaphore,
      @NonNull String fullKey,
      @Nullable SemaphoreMetrics semaphoreMetrics) {
    return new PermitHandle(true, permitId, semaphore, fullKey, semaphoreMetrics);
  }

  /**
   * Creates a handle representing a failed permit acquisition.
   *
   * @return a handle with {@code isAcquired() == false}
   */
  @NonNull
  static PermitHandle notAcquired() {
    return new PermitHandle(false, null, null, null, null);
  }

  /**
   * Returns whether the permit was successfully acquired.
   *
   * @return true if a permit is held by this handle, false otherwise
   */
  public boolean isAcquired() {
    return acquired;
  }

  /**
   * Returns the permit ID if the permit was acquired.
   *
   * @return the permit ID, or null if the permit was not acquired
   */
  @Nullable
  public String permitId() {
    return permitId;
  }

  /**
   * Releases the permit if it was acquired. Safe to call multiple times or if the permit was never
   * acquired (no-op in both cases).
   */
  @Override
  public void close() {
    if (!acquired || semaphore == null || permitId == null) {
      return;
    }
    try {
      semaphore.release(permitId);
      if (semaphoreMetrics != null) {
        semaphoreMetrics.recordHeldTime(System.currentTimeMillis() - heldStartTime);
      }
      LOG.debug("Permit [{}] released from [{}] via handle", permitId, fullKey);
    } catch (IllegalArgumentException e) {
      LOG.warn(
          "Permit [{}] was already released (possibly expired) from [{}]: {}",
          permitId,
          fullKey,
          e.getMessage());
    } catch (Exception e) {
      LOG.warn("Failed to release permit [{}] from [{}]", permitId, fullKey, e);
    }
  }
}
