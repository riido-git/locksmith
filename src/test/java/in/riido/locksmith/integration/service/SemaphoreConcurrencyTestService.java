package in.riido.locksmith.integration.service;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Service interface for semaphore concurrency tests.
 *
 * @author Garvit Joshi
 * @since 2.0.0
 */
public interface SemaphoreConcurrencyTestService {

  /** Track concurrent executions with configurable permit limit. */
  void trackExecution(
      AtomicInteger activeCount,
      AtomicInteger maxConcurrent,
      AtomicInteger completedCount,
      int sleepMs);

  /** Test with 2 permits. */
  void twoPermitMethod(AtomicInteger activeCount, AtomicInteger maxConcurrent);

  /** Test with 10 permits for high concurrency. */
  void tenPermitMethod(AtomicInteger activeCount, AtomicInteger maxConcurrent);

  /** Test permit acquisition with skip on failure. */
  boolean skipOnFailure(AtomicInteger successCount);

  /** Test permit acquisition with wait mode. */
  void waitForPermit(AtomicInteger counter);

  /** Test isolated key execution. */
  void isolatedKeyMethod(String key, AtomicInteger counter);

  /** Increment counter with permit protection. */
  void protectedIncrement(AtomicInteger counter);

  /** Long running operation to test permit holding. */
  void longRunningOperation(AtomicBoolean isExecuting, int durationMs);

  /** Check if can acquire permit while another is executing. */
  boolean canAcquireWhileOtherExecuting();

  /** Track execution order. */
  void trackOrder(int id, List<Integer> order);
}
