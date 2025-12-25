package in.riido.locksmith.integration.service;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** Test service interface for virtual thread integration tests. */
public interface VirtualThreadTestService {
  void exclusiveLockMethod(AtomicInteger activeThreads, AtomicBoolean concurrentExecution);

  boolean tryAcquireLock();

  boolean contentionTestMethod(AtomicInteger activeThreads, AtomicBoolean concurrentExecution);

  void readOperation(AtomicInteger currentReaders, AtomicInteger maxConcurrentReaders);

  void longReadOperation(AtomicBoolean readerActive, CountDownLatch started);

  boolean tryWriteOperation();

  void isolatedLockMethod(
      String key, AtomicInteger concurrentExecutions, AtomicInteger maxConcurrentExecutions);

  void waitAndExecuteMethod(int index, List<Integer> order);

  void waitForLockMethod(AtomicInteger activeThreads, AtomicBoolean concurrentExecution);

  void performanceTestMethod(String key);
}
