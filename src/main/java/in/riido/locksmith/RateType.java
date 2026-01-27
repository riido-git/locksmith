package in.riido.locksmith;

/**
 * Defines the scope of rate limiting.
 *
 * @author Garvit Joshi
 * @since 3.0.0
 */
public enum RateType {

  /**
   * Rate limit shared across all clients/instances. Total permits are distributed among all
   * callers.
   */
  OVERALL,

  /**
   * Rate limit applied per Redisson client instance. Each instance gets its own rate limit quota.
   */
  PER_CLIENT
}
