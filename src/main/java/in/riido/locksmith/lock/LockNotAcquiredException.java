package in.riido.locksmith.lock;

import in.riido.locksmith.LocksmithException;
import java.time.Duration;
import org.jspecify.annotations.NonNull;

/**
 * Thrown when a lock is not acquired within its wait time and the failure policy is {@code THROW}.
 */
public class LockNotAcquiredException extends LocksmithException {

  /** The full Redis key, including the prefix. */
  private final @NonNull String key;

  /** How long the caller waited. */
  private final @NonNull Duration waitTime;

  /**
   * Creates the exception for a lock that was not acquired.
   *
   * @param key the full Redis key, including the prefix
   * @param waitTime how long the caller waited
   */
  public LockNotAcquiredException(@NonNull String key, @NonNull Duration waitTime) {
    super("Lock [" + key + "] not acquired within " + waitTime);
    this.key = key;
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
   * Returns how long the caller waited.
   *
   * @return the wait time
   */
  public @NonNull Duration waitTime() {
    return waitTime;
  }
}
