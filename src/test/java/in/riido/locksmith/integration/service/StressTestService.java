package in.riido.locksmith.integration.service;

/** Test service interface for stress and performance tests. */
public interface StressTestService {
  boolean highVolumeMethod();

  void multiKeyMethod(String key);

  void latencyMeasureMethod();

  void throughputMethod();

  void resourceLeakTestMethod();

  void exceptionThrowingMethod();

  boolean lockAfterExceptionMethod();

  void sustainedLoadMethod();
}
