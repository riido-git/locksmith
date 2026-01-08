package in.riido.locksmith;

/**
 * Defines the behavior when attempting to acquire a distributed lock or semaphore permit.
 *
 * @author Garvit Joshi
 * @since 1.0.0
 */
public enum AcquisitionMode {

  /**
   * Immediately skip execution if the lock is already held or no permit is available. Does not wait
   * for availability.
   */
  SKIP_IMMEDIATELY,

  /**
   * Wait for a configured duration to acquire the lock or permit. If it cannot be acquired within
   * the wait time, skip execution.
   */
  WAIT_AND_SKIP
}
