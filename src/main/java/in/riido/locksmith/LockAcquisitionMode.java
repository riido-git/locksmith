package in.riido.locksmith;

/**
 * Defines the behavior when attempting to acquire a distributed lock.
 *
 * @author Garvit Joshi
 * @since 1.0.0
 */
public enum LockAcquisitionMode {

  /**
   * Immediately skip execution if the lock is already held by another instance. Does not wait for
   * the lock to become available.
   */
  SKIP_IMMEDIATELY,

  /**
   * Wait for a configured duration to acquire the lock. If the lock cannot be acquired within the
   * wait time, skip execution.
   */
  WAIT_AND_SKIP
}
