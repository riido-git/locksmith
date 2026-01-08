package in.riido.locksmith.integration.service;

import in.riido.locksmith.DistributedSemaphore;
import in.riido.locksmith.LeaseExpirationBehavior;
import in.riido.locksmith.LockAcquisitionMode;
import in.riido.locksmith.handler.SemaphoreReturnDefaultHandler;
import in.riido.locksmith.handler.SemaphoreThrowExceptionHandler;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Implementation of SemaphoreIntegrationTestService with @DistributedSemaphore annotations.
 *
 * @author Garvit Joshi
 * @since 2.0.0
 */
public class SemaphoreIntegrationTestServiceImpl implements SemaphoreIntegrationTestService {

  @Override
  @DistributedSemaphore(key = "simple-semaphore", permits = 3)
  public void simplePermitMethod() {
    // Simple method that acquires a permit
  }

  @Override
  @DistributedSemaphore(key = "#{#resourceId}", permits = 5)
  public String permitMethodWithSpelKey(String resourceId) {
    return "processed-" + resourceId;
  }

  @Override
  @DistributedSemaphore(
      key = "limited-permits",
      permits = 3,
      skipHandler = SemaphoreReturnDefaultHandler.class)
  public boolean tryAcquirePermit(AtomicInteger activeCount, AtomicInteger maxConcurrent) {
    int current = activeCount.incrementAndGet();
    try {
      // Track maximum concurrent executions
      maxConcurrent.updateAndGet(max -> Math.max(max, current));
      try {
        Thread.sleep(100);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
      return true;
    } finally {
      activeCount.decrementAndGet();
    }
  }

  @Override
  @DistributedSemaphore(key = "hold-permit", permits = 2)
  public void holdPermitForDuration(CountDownLatch started, CountDownLatch canRelease) {
    started.countDown();
    try {
      canRelease.await();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  @Override
  @DistributedSemaphore(
      key = "throw-on-not-acquired",
      permits = 1,
      skipHandler = SemaphoreThrowExceptionHandler.class)
  public void throwOnPermitNotAcquired() {
    try {
      Thread.sleep(500);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  @Override
  @DistributedSemaphore(
      key = "return-default",
      permits = 1,
      skipHandler = SemaphoreReturnDefaultHandler.class)
  public Object returnDefaultOnPermitNotAcquired() {
    try {
      Thread.sleep(200);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
    return "executed";
  }

  @Override
  @DistributedSemaphore(
      key = "wait-and-acquire",
      permits = 2,
      mode = LockAcquisitionMode.WAIT_AND_SKIP,
      waitTime = "5s",
      skipHandler = SemaphoreReturnDefaultHandler.class)
  public boolean waitAndAcquirePermit(AtomicInteger counter) {
    counter.incrementAndGet();
    try {
      Thread.sleep(100);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
    return true;
  }

  @Override
  @DistributedSemaphore(
      key = "lease-expiration",
      permits = 3,
      leaseTime = "500ms",
      onLeaseExpired = LeaseExpirationBehavior.LOG_WARNING)
  public void permitWithLeaseExpiration() {
    try {
      Thread.sleep(600); // Sleep longer than lease time
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  @Override
  @DistributedSemaphore(
      key = "lease-throw",
      permits = 2,
      leaseTime = "300ms",
      onLeaseExpired = LeaseExpirationBehavior.THROW_EXCEPTION)
  public void permitWithThrowOnLeaseExpired() {
    try {
      Thread.sleep(400); // Sleep longer than lease time
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  @Override
  @DistributedSemaphore(
      key = "concurrent-tracking",
      permits = 5,
      mode = LockAcquisitionMode.WAIT_AND_SKIP,
      waitTime = "10s")
  public void trackConcurrentExecution(
      AtomicInteger activeCount,
      AtomicInteger maxConcurrent,
      AtomicInteger completedCount,
      int sleepMs) {
    int current = activeCount.incrementAndGet();
    try {
      maxConcurrent.updateAndGet(max -> Math.max(max, current));
      try {
        Thread.sleep(sleepMs);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
      completedCount.incrementAndGet();
    } finally {
      activeCount.decrementAndGet();
    }
  }

  @Override
  @DistributedSemaphore(
      key = "#{#key}",
      permits = 3,
      mode = LockAcquisitionMode.WAIT_AND_SKIP,
      waitTime = "5s")
  public void multiKeyPermit(String key, AtomicInteger counter) {
    counter.incrementAndGet();
    try {
      Thread.sleep(50);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  @Override
  @DistributedSemaphore(
      key = "high-contention",
      permits = 5,
      skipHandler = SemaphoreReturnDefaultHandler.class)
  public boolean highContentionPermit(AtomicInteger successCount, AtomicInteger skipCount) {
    successCount.incrementAndGet();
    try {
      Thread.sleep(50);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
    return true;
  }

  @Override
  @DistributedSemaphore(
      key = "ordered-execution",
      permits = 2,
      mode = LockAcquisitionMode.WAIT_AND_SKIP,
      waitTime = "30s")
  public void orderedPermitAcquisition(int id, List<Integer> executionOrder) {
    synchronized (executionOrder) {
      executionOrder.add(id);
    }
    try {
      Thread.sleep(50);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  @Override
  @DistributedSemaphore(key = "exception-test", permits = 2)
  public void permitWithException() {
    throw new RuntimeException("Intentional exception for testing");
  }

  @Override
  @DistributedSemaphore(
      key = "exception-test",
      permits = 2,
      skipHandler = SemaphoreReturnDefaultHandler.class)
  public boolean acquirePermitAfterException() {
    return true;
  }
}
