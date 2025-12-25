package in.riido.locksmith.integration.service;

import in.riido.locksmith.DistributedLock;
import in.riido.locksmith.LockAcquisitionMode;
import in.riido.locksmith.handler.ReturnDefaultHandler;

/** Test service implementation for stress and performance tests. */
public class StressTestServiceImpl implements StressTestService {

  @Override
  @DistributedLock(key = "high-volume", skipHandler = ReturnDefaultHandler.class)
  public boolean highVolumeMethod() {
    return true;
  }

  @Override
  @DistributedLock(key = "#key", mode = LockAcquisitionMode.WAIT_AND_SKIP, waitTime = "5s")
  public void multiKeyMethod(String key) {
    // Minimal work
  }

  @Override
  @DistributedLock(key = "latency-test")
  public void latencyMeasureMethod() {
    // Minimal work to measure lock overhead
  }

  @Override
  @DistributedLock(
      key = "throughput-test",
      mode = LockAcquisitionMode.WAIT_AND_SKIP,
      waitTime = "10s")
  public void throughputMethod() {
    // Minimal work
  }

  @Override
  @DistributedLock(key = "resource-leak-test")
  public void resourceLeakTestMethod() {
    // Minimal work
  }

  @Override
  @DistributedLock(key = "exception-test")
  public void exceptionThrowingMethod() {
    throw new RuntimeException("Test exception");
  }

  @Override
  @DistributedLock(key = "exception-test", skipHandler = ReturnDefaultHandler.class)
  public boolean lockAfterExceptionMethod() {
    return true;
  }

  @Override
  @DistributedLock(
      key = "sustained-load",
      mode = LockAcquisitionMode.WAIT_AND_SKIP,
      waitTime = "5s")
  public void sustainedLoadMethod() {
    // Minimal work
  }
}
