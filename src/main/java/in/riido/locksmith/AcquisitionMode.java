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
  SKIP_IMMEDIATELY("immediate"),

  /**
   * Wait for a configured duration to acquire the lock or permit. If it cannot be acquired within
   * the wait time, skip execution.
   */
  WAIT_AND_SKIP("timeout");

  private final String metricsReason;

  AcquisitionMode(String metricsReason) {
    this.metricsReason = metricsReason;
  }

  /**
   * Returns the reason string used for metrics tagging when acquisition fails in this mode.
   *
   * @return the metrics reason tag value
   */
  public String metricsReason() {
    return metricsReason;
  }
}
