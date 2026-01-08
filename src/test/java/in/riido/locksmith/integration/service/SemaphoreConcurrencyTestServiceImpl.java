package in.riido.locksmith.integration.service;

import in.riido.locksmith.DistributedSemaphore;
import in.riido.locksmith.LockAcquisitionMode;
import in.riido.locksmith.handler.SemaphoreReturnDefaultHandler;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Implementation of SemaphoreConcurrencyTestService.
 *
 * @author Garvit Joshi
 * @since 2.0.0
 */
public class SemaphoreConcurrencyTestServiceImpl implements SemaphoreConcurrencyTestService {

  @Override
  @DistributedSemaphore(
      key = "track-execution",
      permits = 5,
      mode = LockAcquisitionMode.WAIT_AND_SKIP,
      waitTime = "30s")
  public void trackExecution(
      AtomicInteger activeCount,
      AtomicInteger maxConcurrent,
      AtomicInteger completedCount,
      int sleepMs) {
    int current = activeCount.incrementAndGet();
    try {
      maxConcurrent.updateAndGet(max -> Math.max(max, current));
      sleep(sleepMs);
      completedCount.incrementAndGet();
    } finally {
      activeCount.decrementAndGet();
    }
  }

  @Override
  @DistributedSemaphore(
      key = "two-permit",
      permits = 2,
      mode = LockAcquisitionMode.WAIT_AND_SKIP,
      waitTime = "10s")
  public void twoPermitMethod(AtomicInteger activeCount, AtomicInteger maxConcurrent) {
    int current = activeCount.incrementAndGet();
    try {
      maxConcurrent.updateAndGet(max -> Math.max(max, current));
      sleep(100);
    } finally {
      activeCount.decrementAndGet();
    }
  }

  @Override
  @DistributedSemaphore(
      key = "ten-permit",
      permits = 10,
      mode = LockAcquisitionMode.WAIT_AND_SKIP,
      waitTime = "30s")
  public void tenPermitMethod(AtomicInteger activeCount, AtomicInteger maxConcurrent) {
    int current = activeCount.incrementAndGet();
    try {
      maxConcurrent.updateAndGet(max -> Math.max(max, current));
      sleep(50);
    } finally {
      activeCount.decrementAndGet();
    }
  }

  @Override
  @DistributedSemaphore(
      key = "skip-on-failure",
      permits = 3,
      skipHandler = SemaphoreReturnDefaultHandler.class)
  public boolean skipOnFailure(AtomicInteger successCount) {
    successCount.incrementAndGet();
    sleep(100);
    return true;
  }

  @Override
  @DistributedSemaphore(
      key = "wait-for-permit",
      permits = 3,
      mode = LockAcquisitionMode.WAIT_AND_SKIP,
      waitTime = "15s")
  public void waitForPermit(AtomicInteger counter) {
    counter.incrementAndGet();
    sleep(50);
  }

  @Override
  @DistributedSemaphore(
      key = "#{#key}",
      permits = 2,
      mode = LockAcquisitionMode.WAIT_AND_SKIP,
      waitTime = "10s")
  public void isolatedKeyMethod(String key, AtomicInteger counter) {
    counter.incrementAndGet();
    sleep(50);
  }

  @Override
  @DistributedSemaphore(
      key = "protected-counter",
      permits = 1,
      mode = LockAcquisitionMode.WAIT_AND_SKIP,
      waitTime = "30s")
  public void protectedIncrement(AtomicInteger counter) {
    // With permits=1, this acts like a distributed lock
    int current = counter.get();
    sleep(10); // Simulate some processing
    counter.set(current + 1);
  }

  @Override
  @DistributedSemaphore(
      key = "long-running",
      permits = 1,
      mode = LockAcquisitionMode.WAIT_AND_SKIP,
      waitTime = "5s")
  public void longRunningOperation(AtomicBoolean isExecuting, int durationMs) {
    isExecuting.set(true);
    try {
      sleep(durationMs);
    } finally {
      isExecuting.set(false);
    }
  }

  @Override
  @DistributedSemaphore(
      key = "long-running",
      permits = 1,
      skipHandler = SemaphoreReturnDefaultHandler.class)
  public boolean canAcquireWhileOtherExecuting() {
    return true;
  }

  @Override
  @DistributedSemaphore(
      key = "order-tracking",
      permits = 2,
      mode = LockAcquisitionMode.WAIT_AND_SKIP,
      waitTime = "30s")
  public void trackOrder(int id, List<Integer> order) {
    synchronized (order) {
      order.add(id);
    }
    sleep(30);
  }

  private void sleep(int ms) {
    try {
      Thread.sleep(ms);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
