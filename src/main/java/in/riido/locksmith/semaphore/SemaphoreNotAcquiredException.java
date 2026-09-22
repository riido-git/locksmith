package in.riido.locksmith.semaphore;

import in.riido.locksmith.LocksmithException;
import java.time.Duration;
import org.jspecify.annotations.NonNull;

/**
 * Thrown when a semaphore permit is not acquired within its wait time and the failure policy is
 * {@code THROW}.
 */
public class SemaphoreNotAcquiredException extends LocksmithException {

  /** The full Redis key, including the prefix. */
  private final @NonNull String key;

  /** The permit count configured for the semaphore. */
  private final int permits;

  /** How long the caller waited. */
  private final @NonNull Duration waitTime;

  /**
   * Creates the exception for a permit that was not acquired.
   *
   * @param key the full Redis key, including the prefix
   * @param permits the permit count configured for the semaphore
   * @param waitTime how long the caller waited
   */
  public SemaphoreNotAcquiredException(
      @NonNull String key, int permits, @NonNull Duration waitTime) {
    super(
        "Semaphore ["
            + key
            + "] permit not acquired within "
            + waitTime
            + " (permits "
            + permits
            + ")");
    this.key = key;
    this.permits = permits;
    this.waitTime = waitTime;
  }

  /**
   * Returns the full Redis key, including the prefix.
   *
   * @return the key
   */
  public @NonNull String key() {
    return key;
  }

  /**
   * Returns the permit count configured for the semaphore.
   *
   * @return the permit count
   */
  public int permits() {
    return permits;
  }

  /**
   * Returns how long the caller waited.
   *
   * @return the wait time
   */
  public @NonNull Duration waitTime() {
    return waitTime;
  }
}
