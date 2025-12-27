package in.riido.locksmith.integration.service;

import in.riido.locksmith.DistributedLock;
import in.riido.locksmith.LockAcquisitionMode;
import in.riido.locksmith.LockType;
import in.riido.locksmith.handler.ReturnDefaultHandler;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** Test service implementation for concurrent access tests. */
public class ConcurrencyTestServiceImpl implements ConcurrencyTestService {

  @Override
  @DistributedLock(
      key = "exclusive-lock",
      mode = LockAcquisitionMode.WAIT_AND_SKIP,
      waitTime = "5s")
  public void exclusiveLockMethod(AtomicInteger activeThreads, AtomicBoolean concurrentExecution) {
    int current = activeThreads.incrementAndGet();
    if (current > 1) {
      concurrentExecution.set(true);
    }
    try {
      Thread.sleep(100);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
    activeThreads.decrementAndGet();
  }

  @Override
  @DistributedLock(key = "contention-lock", skipHandler = ReturnDefaultHandler.class)
  public boolean contentionTestMethod(
      AtomicInteger activeThreads, AtomicBoolean concurrentExecution) {
    int current = activeThreads.incrementAndGet();
    if (current > 1) {
      concurrentExecution.set(true);
    }
    try {
      Thread.sleep(50);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
    activeThreads.decrementAndGet();
    return true;
  }

  @Override
  @DistributedLock(key = "single-exec-lock", skipHandler = ReturnDefaultHandler.class)
  public void singleExecutionMethod(AtomicInteger executionCount) {
    executionCount.incrementAndGet();
    try {
      Thread.sleep(500);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  @Override
  @DistributedLock(key = "counter-lock", skipHandler = ReturnDefaultHandler.class)
  public boolean incrementCounter(AtomicInteger counter) {
    counter.incrementAndGet();
    return true;
  }

  @Override
  @DistributedLock(key = "order-lock", mode = LockAcquisitionMode.WAIT_AND_SKIP, waitTime = "10s")
  public void orderedExecutionMethod(int index, List<Integer> order) {
    order.add(index);
    try {
      Thread.sleep(50);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  @Override
  @DistributedLock(
      key = "rw-concurrent",
      type = LockType.READ,
      skipHandler = ReturnDefaultHandler.class)
  public void readOperation(AtomicInteger currentReaders, AtomicInteger maxConcurrentReaders) {
    int current = currentReaders.incrementAndGet();
    maxConcurrentReaders.updateAndGet(max -> Math.max(max, current));
    try {
      Thread.sleep(300);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
    currentReaders.decrementAndGet();
  }

  @Override
  @DistributedLock(
      key = "rw-block-test",
      type = LockType.WRITE,
      skipHandler = ReturnDefaultHandler.class)
  public void writeOperation(AtomicBoolean writerActive, CountDownLatch started) {
    writerActive.set(true);
    started.countDown();
    try {
      Thread.sleep(1000);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
    writerActive.set(false);
  }

  @Override
  @DistributedLock(
      key = "rw-block-test",
      type = LockType.READ,
      skipHandler = ReturnDefaultHandler.class)
  public boolean readDuringWrite() {
    return true;
  }

  @Override
  @DistributedLock(key = "#{#key}", skipHandler = ReturnDefaultHandler.class)
  public void isolatedLockMethod(
      String key, AtomicInteger concurrentExecutions, AtomicInteger maxConcurrentExecutions) {
    int current = concurrentExecutions.incrementAndGet();
    maxConcurrentExecutions.updateAndGet(max -> Math.max(max, current));
    try {
      Thread.sleep(300);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
    concurrentExecutions.decrementAndGet();
  }
}
