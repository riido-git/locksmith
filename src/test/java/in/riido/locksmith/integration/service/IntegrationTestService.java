package in.riido.locksmith.integration.service;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

/** Test service interface for distributed lock integration tests. */
public interface IntegrationTestService {
  String simpleLockedMethod();

  String lockedMethodWithSpelKey(String userId);

  void longRunningMethod(CountDownLatch started, AtomicInteger count);

  String tryAcquireSameLock();

  void holdLockForThrowTest(CountDownLatch started);

  String throwOnLockNotAcquired();

  void shortHoldingMethod(AtomicInteger count);

  void waitAndExecuteMethod(AtomicInteger count);

  void readLockMethod(
      AtomicInteger concurrentReaders,
      AtomicInteger maxConcurrentReaders,
      CountDownLatch allStarted);

  void holdReadLock(CountDownLatch acquired);

  String tryWriteLock(AtomicInteger count);

  void holdWriteLock(CountDownLatch acquired);

  String tryReadLock(AtomicInteger count);

  String autoRenewMethod();
}
