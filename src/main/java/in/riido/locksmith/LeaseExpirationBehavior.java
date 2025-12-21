package in.riido.locksmith;

/**
 * Defines the behavior when a method's execution time exceeds the configured lease duration.
 *
 * <p>When a method runs longer than its lock's lease time, the lock may expire while the method is
 * still executing. This can lead to concurrent access by multiple instances. This enum configures
 * how to handle detection of such scenarios after method completion.
 *
 * @author Garvit Joshi
 * @since 1.2.0
 */
public enum LeaseExpirationBehavior {

  /**
   * Log a warning when execution time exceeds lease duration. This is the default behavior,
   * providing visibility into potential issues without disrupting the application.
   */
  LOG_WARNING,

  /**
   * Throw a {@link in.riido.locksmith.exception.LeaseExpiredException} after the method completes
   * if execution time exceeded lease duration. Use this for strict enforcement where such
   * violations should be treated as errors.
   */
  THROW_EXCEPTION,

  /**
   * Silently ignore when execution time exceeds lease duration. Use this only when you're certain
   * the extended execution is acceptable and won't cause issues.
   */
  IGNORE
}
