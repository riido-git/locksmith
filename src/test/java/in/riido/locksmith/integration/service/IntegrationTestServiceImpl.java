package in.riido.locksmith.integration.service;

import in.riido.locksmith.DistributedLock;
import in.riido.locksmith.LockAcquisitionMode;
import in.riido.locksmith.LockType;
import in.riido.locksmith.handler.ReturnDefaultHandler;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

/** Test service implementation for distributed lock integration tests. */
public class IntegrationTestServiceImpl implements IntegrationTestService {

  @Override
  @DistributedLock(key = "simple-lock")
  public String simpleLockedMethod() {
    return "executed";
  }

  @Override
  @DistributedLock(key = "#userId")
  public String lockedMethodWithSpelKey(String userId) {
    return "processed:" + userId;
  }

  @Override
  @DistributedLock(key = "long-running-lock", skipHandler = ReturnDefaultHandler.class)
  public void longRunningMethod(CountDownLatch started, AtomicInteger count) {
    started.countDown();
    try {
      Thread.sleep(2000);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
    count.incrementAndGet();
  }

  @Override
  @DistributedLock(key = "long-running-lock", skipHandler = ReturnDefaultHandler.class)
  public String tryAcquireSameLock() {
    return "should-not-execute";
  }

  @Override
  @DistributedLock(key = "throw-test-lock", skipHandler = ReturnDefaultHandler.class)
  public void holdLockForThrowTest(CountDownLatch started) {
    started.countDown();
    try {
      Thread.sleep(2000);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  @Override
  @DistributedLock(key = "throw-test-lock")
  public String throwOnLockNotAcquired() {
    return "should-not-execute";
  }

  @Override
  @DistributedLock(key = "wait-test-lock", skipHandler = ReturnDefaultHandler.class)
  public void shortHoldingMethod(AtomicInteger count) {
    try {
      Thread.sleep(100);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
    count.incrementAndGet();
  }

  @Override
  @DistributedLock(
      key = "wait-test-lock",
      mode = LockAcquisitionMode.WAIT_AND_SKIP,
      waitTime = "5s")
  public void waitAndExecuteMethod(AtomicInteger count) {
    count.incrementAndGet();
  }

  @Override
  @DistributedLock(key = "rw-lock", type = LockType.READ, skipHandler = ReturnDefaultHandler.class)
  public void readLockMethod(
      AtomicInteger concurrentReaders,
      AtomicInteger maxConcurrentReaders,
      CountDownLatch allStarted) {
    int current = concurrentReaders.incrementAndGet();
    maxConcurrentReaders.updateAndGet(max -> Math.max(max, current));
    allStarted.countDown();
    try {
      Thread.sleep(500);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
    concurrentReaders.decrementAndGet();
  }

  @Override
  @DistributedLock(
      key = "rw-test-lock",
      type = LockType.READ,
      skipHandler = ReturnDefaultHandler.class)
  public void holdReadLock(CountDownLatch acquired) {
    acquired.countDown();
    try {
      Thread.sleep(2000);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  @Override
  @DistributedLock(
      key = "rw-test-lock",
      type = LockType.WRITE,
      skipHandler = ReturnDefaultHandler.class)
  public String tryWriteLock(AtomicInteger count) {
    count.incrementAndGet();
    return "write-executed";
  }

  @Override
  @DistributedLock(
      key = "rw-test-lock2",
      type = LockType.WRITE,
      skipHandler = ReturnDefaultHandler.class)
  public void holdWriteLock(CountDownLatch acquired) {
    acquired.countDown();
    try {
      Thread.sleep(2000);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  @Override
  @DistributedLock(
      key = "rw-test-lock2",
      type = LockType.READ,
      skipHandler = ReturnDefaultHandler.class)
  public String tryReadLock(AtomicInteger count) {
    count.incrementAndGet();
    return "read-executed";
  }

  @Override
  @DistributedLock(key = "auto-renew-lock", autoRenew = true)
  public String autoRenewMethod() {
    try {
      Thread.sleep(2500);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
    return "completed";
  }
}
