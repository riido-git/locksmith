package in.riido.locksmith.integration.service;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Service interface for distributed semaphore integration tests.
 *
 * @author Garvit Joshi
 * @since 2.0.0
 */
public interface SemaphoreIntegrationTestService {

  // Basic semaphore operations
  void simplePermitMethod();

  String permitMethodWithSpelKey(String resourceId);

  // Permit limit tests
  boolean tryAcquirePermit(AtomicInteger activeCount, AtomicInteger maxConcurrent);

  void holdPermitForDuration(CountDownLatch started, CountDownLatch canRelease);

  // Skip handler tests
  void throwOnPermitNotAcquired();

  Object returnDefaultOnPermitNotAcquired();

  // Wait mode tests
  boolean waitAndAcquirePermit(AtomicInteger counter);

  // Lease expiration tests
  void permitWithLeaseExpiration();

  void permitWithThrowOnLeaseExpired();

  // Concurrent execution tracking
  void trackConcurrentExecution(
      AtomicInteger activeCount,
      AtomicInteger maxConcurrent,
      AtomicInteger completedCount,
      int sleepMs);

  // Multi-key tests
  void multiKeyPermit(String key, AtomicInteger counter);

  // High contention tests
  boolean highContentionPermit(AtomicInteger successCount, AtomicInteger skipCount);

  // Order tracking
  void orderedPermitAcquisition(int id, List<Integer> executionOrder);

  // Exception handling
  void permitWithException();

  boolean acquirePermitAfterException();
}
