package in.riido.locksmith.integration.service;

import in.riido.locksmith.AcquisitionMode;
import in.riido.locksmith.RateLimit;
import in.riido.locksmith.handler.ratelimit.RateLimitReturnDefaultHandler;
import in.riido.locksmith.handler.ratelimit.RateLimitThrowExceptionHandler;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Implementation of rate limit integration test service.
 *
 * @author Garvit Joshi
 * @since 2.1.0
 */
public class RateLimitIntegrationTestServiceImpl implements RateLimitIntegrationTestService {

  @Override
  @RateLimit(key = "simple-rate-limit", permits = 10, interval = "1s")
  public String simpleRateLimitedMethod() {
    return "executed";
  }

  @Override
  @RateLimit(key = "#{#resourceId}", permits = 5, interval = "1s")
  public String rateLimitedMethodWithSpelKey(String resourceId) {
    return "processed:" + resourceId;
  }

  @Override
  @RateLimit(
      key = "tracking-rate-limit",
      permits = 5,
      interval = "1s",
      mode = AcquisitionMode.SKIP_IMMEDIATELY,
      skipHandler = RateLimitReturnDefaultHandler.class)
  public void trackingMethod(AtomicInteger counter) {
    counter.incrementAndGet();
  }

  @Override
  @RateLimit(
      key = "wait-rate-limit",
      permits = 3,
      interval = "1s",
      mode = AcquisitionMode.WAIT_AND_SKIP,
      waitTime = "10s")
  public void waitAndAcquireMethod(AtomicInteger counter) {
    counter.incrementAndGet();
  }

  @Override
  @RateLimit(
      key = "throw-rate-limit",
      permits = 1,
      interval = "10s",
      mode = AcquisitionMode.SKIP_IMMEDIATELY,
      skipHandler = RateLimitThrowExceptionHandler.class)
  public String throwOnLimitExceeded() {
    return "executed";
  }

  @Override
  @RateLimit(
      key = "default-rate-limit",
      permits = 1,
      interval = "10s",
      mode = AcquisitionMode.SKIP_IMMEDIATELY,
      skipHandler = RateLimitReturnDefaultHandler.class)
  public String returnDefaultOnLimitExceeded() {
    return "executed";
  }

  @Override
  @RateLimit(
      key = "throughput-rate-limit",
      permits = 100,
      interval = "1s",
      mode = AcquisitionMode.WAIT_AND_SKIP,
      waitTime = "30s")
  public void shortWorkMethod(AtomicInteger counter) {
    counter.incrementAndGet();
  }

  @Override
  @RateLimit(
      key = "#{#key}",
      permits = 5,
      interval = "1s",
      mode = AcquisitionMode.WAIT_AND_SKIP,
      waitTime = "10s")
  public void isolatedKeyMethod(String key, AtomicInteger counter) {
    counter.incrementAndGet();
  }

  @Override
  @RateLimit(key = "sleep-rate-limit", permits = 10, interval = "1s")
  public void sleepingMethod(long millis) {
    try {
      Thread.sleep(millis);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
