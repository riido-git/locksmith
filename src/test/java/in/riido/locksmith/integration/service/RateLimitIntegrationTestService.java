package in.riido.locksmith.integration.service;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Service interface for rate limit integration tests.
 *
 * @author Garvit Joshi
 * @since 2.1.0
 */
public interface RateLimitIntegrationTestService {

  /** Simple rate limited method with default settings. */
  String simpleRateLimitedMethod();

  /** Rate limited method with SpEL key resolution. */
  String rateLimitedMethodWithSpelKey(String resourceId);

  /** Rate limited method that tracks execution count. */
  void trackingMethod(AtomicInteger counter);

  /** Rate limited method with WAIT_AND_SKIP mode. */
  void waitAndAcquireMethod(AtomicInteger counter);

  /** Rate limited method that throws exception when limit exceeded. */
  String throwOnLimitExceeded();

  /** Rate limited method that returns default when limit exceeded. */
  String returnDefaultOnLimitExceeded();

  /** Rate limited method with short work for throughput tests. */
  void shortWorkMethod(AtomicInteger counter);

  /** Rate limited method with isolated key. */
  void isolatedKeyMethod(String key, AtomicInteger counter);

  /** Rate limited method that sleeps to test timing. */
  void sleepingMethod(long millis);
}
