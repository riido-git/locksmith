package in.riido.locksmith.integration.service;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** Test service interface for concurrent access tests. */
public interface ConcurrencyTestService {
  void exclusiveLockMethod(AtomicInteger activeThreads, AtomicBoolean concurrentExecution);

  boolean contentionTestMethod(AtomicInteger activeThreads, AtomicBoolean concurrentExecution);

  void singleExecutionMethod(AtomicInteger executionCount);

  boolean incrementCounter(AtomicInteger counter);

  void orderedExecutionMethod(int index, List<Integer> order);

  void readOperation(AtomicInteger currentReaders, AtomicInteger maxConcurrentReaders);

  void writeOperation(AtomicBoolean writerActive, CountDownLatch started);

  boolean readDuringWrite();

  void isolatedLockMethod(
      String key, AtomicInteger concurrentExecutions, AtomicInteger maxConcurrentExecutions);
}
