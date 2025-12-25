package in.riido.locksmith.integration.service;

import in.riido.locksmith.DistributedLock;
import in.riido.locksmith.LockAcquisitionMode;
import in.riido.locksmith.LockType;
import in.riido.locksmith.handler.ReturnDefaultHandler;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** Test service implementation for virtual thread integration tests. */
public class VirtualThreadTestServiceImpl implements VirtualThreadTestService {

  @Override
  @DistributedLock(
      key = "vt-exclusive-lock",
      mode = LockAcquisitionMode.WAIT_AND_SKIP,
      waitTime = "30s")
  public void exclusiveLockMethod(AtomicInteger activeThreads, AtomicBoolean concurrentExecution) {
    int current = activeThreads.incrementAndGet();
    if (current > 1) {
      concurrentExecution.set(true);
    }
    try {
      Thread.sleep(10);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
    activeThreads.decrementAndGet();
  }

  @Override
  @DistributedLock(key = "vt-try-lock", skipHandler = ReturnDefaultHandler.class)
  public boolean tryAcquireLock() {
    try {
      Thread.sleep(5);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
    return true;
  }

  @Override
  @DistributedLock(key = "vt-contention-lock", skipHandler = ReturnDefaultHandler.class)
  public boolean contentionTestMethod(
      AtomicInteger activeThreads, AtomicBoolean concurrentExecution) {
    int current = activeThreads.incrementAndGet();
    if (current > 1) {
      concurrentExecution.set(true);
    }
    try {
      Thread.sleep(20);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
    activeThreads.decrementAndGet();
    return true;
  }

  @Override
  @DistributedLock(
      key = "vt-rw-concurrent",
      type = LockType.READ,
      skipHandler = ReturnDefaultHandler.class)
  public void readOperation(AtomicInteger currentReaders, AtomicInteger maxConcurrentReaders) {
    int current = currentReaders.incrementAndGet();
    maxConcurrentReaders.updateAndGet(max -> Math.max(max, current));
    try {
      Thread.sleep(100);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
    currentReaders.decrementAndGet();
  }

  @Override
  @DistributedLock(
      key = "vt-rw-block-test",
      type = LockType.READ,
      skipHandler = ReturnDefaultHandler.class)
  public void longReadOperation(AtomicBoolean readerActive, CountDownLatch started) {
    readerActive.set(true);
    started.countDown();
    try {
      Thread.sleep(1000);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
    readerActive.set(false);
  }

  @Override
  @DistributedLock(
      key = "vt-rw-block-test",
      type = LockType.WRITE,
      skipHandler = ReturnDefaultHandler.class)
  public boolean tryWriteOperation() {
    return true;
  }

  @Override
  @DistributedLock(key = "#key", skipHandler = ReturnDefaultHandler.class)
  public void isolatedLockMethod(
      String key, AtomicInteger concurrentExecutions, AtomicInteger maxConcurrentExecutions) {
    int current = concurrentExecutions.incrementAndGet();
    maxConcurrentExecutions.updateAndGet(max -> Math.max(max, current));
    try {
      Thread.sleep(100);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
    concurrentExecutions.decrementAndGet();
  }

  @Override
  @DistributedLock(
      key = "vt-order-lock",
      mode = LockAcquisitionMode.WAIT_AND_SKIP,
      waitTime = "30s")
  public void waitAndExecuteMethod(int index, List<Integer> order) {
    order.add(index);
    try {
      Thread.sleep(10);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  @Override
  @DistributedLock(key = "vt-wait-lock", mode = LockAcquisitionMode.WAIT_AND_SKIP, waitTime = "60s")
  public void waitForLockMethod(AtomicInteger activeThreads, AtomicBoolean concurrentExecution) {
    int current = activeThreads.incrementAndGet();
    if (current > 1) {
      concurrentExecution.set(true);
    }
    try {
      Thread.sleep(5);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
    activeThreads.decrementAndGet();
  }

  @Override
  @DistributedLock(key = "#key", mode = LockAcquisitionMode.WAIT_AND_SKIP, waitTime = "30s")
  public void performanceTestMethod(String key) {
    try {
      Thread.sleep(5);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
