package in.riido.locksmith.aspect;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import in.riido.locksmith.DistributedLock;
import in.riido.locksmith.LeaseExpirationBehavior;
import in.riido.locksmith.LockAcquisitionMode;
import in.riido.locksmith.LockType;
import in.riido.locksmith.autoconfigure.LocksmithProperties;
import in.riido.locksmith.exception.LeaseExpiredException;
import in.riido.locksmith.exception.LockNotAcquiredException;
import in.riido.locksmith.handler.LockContext;
import in.riido.locksmith.handler.LockSkipHandler;
import in.riido.locksmith.handler.ReturnDefaultHandler;
import in.riido.locksmith.handler.ThrowExceptionHandler;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import org.redisson.api.RReadWriteLock;
import org.redisson.api.RedissonClient;

@DisplayName("DistributedLockAspect Tests")
class DistributedLockAspectTest {

  private RedissonClient redissonClient;
  private DistributedLockAspect aspect;
  private ProceedingJoinPoint joinPoint;
  private MethodSignature methodSignature;
  private RLock lock;

  @BeforeEach
  void setUp() {
    redissonClient = mock(RedissonClient.class);
    LocksmithProperties lockProperties =
        new LocksmithProperties(Duration.ofMinutes(10), Duration.ofSeconds(60), "lock:");
    aspect = new DistributedLockAspect(redissonClient, lockProperties);
    joinPoint = mock(ProceedingJoinPoint.class);
    methodSignature = mock(MethodSignature.class);
    lock = mock(RLock.class);

    when(joinPoint.getSignature()).thenReturn(methodSignature);
    when(methodSignature.getDeclaringType()).thenReturn(TestClass.class);
    when(methodSignature.getName()).thenReturn("testMethod");
  }

  private void setupAnnotation(
      String key,
      LockAcquisitionMode mode,
      String leaseTime,
      String waitTime,
      Class<? extends LockSkipHandler> skipHandler) {
    setupAnnotation(
        key,
        mode,
        leaseTime,
        waitTime,
        skipHandler,
        LockType.REENTRANT,
        LeaseExpirationBehavior.IGNORE);
  }

  private void setupAnnotation(
      String key,
      LockAcquisitionMode mode,
      String leaseTime,
      String waitTime,
      Class<? extends LockSkipHandler> skipHandler,
      LockType lockType) {
    setupAnnotation(
        key, mode, leaseTime, waitTime, skipHandler, lockType, LeaseExpirationBehavior.IGNORE);
  }

  private void setupAnnotation(
      String key,
      LockAcquisitionMode mode,
      String leaseTime,
      String waitTime,
      Class<? extends LockSkipHandler> skipHandler,
      LockType lockType,
      LeaseExpirationBehavior onLeaseExpired) {
    DistributedLock annotation = mock(DistributedLock.class);
    when(annotation.key()).thenReturn(key);
    when(annotation.mode()).thenReturn(mode);
    when(annotation.leaseTime()).thenReturn(leaseTime);
    when(annotation.waitTime()).thenReturn(waitTime);
    when(annotation.type()).thenReturn(lockType);
    when(annotation.onLeaseExpired()).thenReturn(onLeaseExpired);
    doReturn(skipHandler).when(annotation).skipHandler();

    Method mockMethod = mock(Method.class);
    when(methodSignature.getMethod()).thenReturn(mockMethod);
    when(mockMethod.getAnnotation(DistributedLock.class)).thenReturn(annotation);
  }

  private static class TestClass {
    public void testMethod() {}
  }

  /** Test class with annotated methods for SpEL testing. */
  public static class SpelTestClass {

    @DistributedLock(key = "#userId")
    public void processUser(String userId) {}

    @DistributedLock(key = "'user-' + #id")
    public void processWithPrefix(Long id) {}

    @DistributedLock(key = "#user.id")
    public void updateUser(TestUser user) {}

    @DistributedLock(key = "static-key")
    public void staticKeyMethod() {}

    @DistributedLock(key = "#value")
    public void processWithBlankValue(String value) {}

    @DistributedLock(key = "#user.name")
    public void processUserName(TestUser user) {}

    public record TestUser(String id, String name) {
      public TestUser(String id) {
        this(id, null);
      }
    }
  }

  @Nested
  @DisplayName("Lock Acquisition Tests - SKIP_IMMEDIATELY Mode")
  class SkipImmediatelyModeTests {

    @Test
    @DisplayName("Should acquire lock and execute method when lock is available")
    void shouldAcquireLockAndExecuteMethod() throws Throwable {
      setupAnnotation(
          "test-lock", LockAcquisitionMode.SKIP_IMMEDIATELY, "", "", ThrowExceptionHandler.class);
      when(redissonClient.getLock("lock:test-lock")).thenReturn(lock);
      when(lock.tryLock(0, 600, TimeUnit.SECONDS)).thenReturn(true);
      when(lock.isHeldByCurrentThread()).thenReturn(true);
      when(joinPoint.proceed()).thenReturn("result");

      Object result = aspect.handleDistributedLock(joinPoint);

      assertEquals("result", result);
      verify(joinPoint).proceed();
      verify(lock).unlock();
    }

    @Test
    @DisplayName(
        "Should skip execution and return null when lock is not available with RETURN_DEFAULT")
    void shouldSkipExecutionWhenLockNotAvailable() throws Throwable {
      setupAnnotation(
          "test-lock", LockAcquisitionMode.SKIP_IMMEDIATELY, "", "", ReturnDefaultHandler.class);
      when(redissonClient.getLock("lock:test-lock")).thenReturn(lock);
      when(lock.tryLock(0, 600, TimeUnit.SECONDS)).thenReturn(false);

      Object result = aspect.handleDistributedLock(joinPoint);

      assertNull(result);
      verify(joinPoint, never()).proceed();
      verify(lock, never()).unlock();
    }

    @Test
    @DisplayName("Should use zero wait time for SKIP_IMMEDIATELY mode")
    void shouldUseZeroWaitTime() throws Throwable {
      setupAnnotation(
          "test-lock", LockAcquisitionMode.SKIP_IMMEDIATELY, "", "", ThrowExceptionHandler.class);
      when(redissonClient.getLock("lock:test-lock")).thenReturn(lock);
      when(lock.tryLock(eq(0L), anyLong(), eq(TimeUnit.SECONDS))).thenReturn(true);
      when(lock.isHeldByCurrentThread()).thenReturn(true);

      aspect.handleDistributedLock(joinPoint);

      verify(lock).tryLock(eq(0L), anyLong(), eq(TimeUnit.SECONDS));
    }
  }

  @Nested
  @DisplayName("InterruptedException Handling Tests")
  class InterruptedExceptionTests {

    @Test
    @DisplayName("Should handle InterruptedException and return default with RETURN_DEFAULT")
    void shouldHandleInterruptedExceptionWithReturnDefault() throws Throwable {
      setupAnnotation(
          "test-lock", LockAcquisitionMode.WAIT_AND_SKIP, "", "", ReturnDefaultHandler.class);
      when(redissonClient.getLock("lock:test-lock")).thenReturn(lock);
      when(lock.tryLock(60, 600, TimeUnit.SECONDS)).thenThrow(new InterruptedException());

      Object result = aspect.handleDistributedLock(joinPoint);

      assertNull(result);
      assertTrue(Thread.currentThread().isInterrupted());
      verify(joinPoint, never()).proceed();
      Thread.interrupted(); // Clear interrupt status for other tests
    }

    @Test
    @DisplayName("Should handle InterruptedException and throw exception with THROW_EXCEPTION")
    void shouldHandleInterruptedExceptionWithThrowException() throws Throwable {
      setupAnnotation(
          "test-lock", LockAcquisitionMode.WAIT_AND_SKIP, "", "", ThrowExceptionHandler.class);
      when(redissonClient.getLock("lock:test-lock")).thenReturn(lock);
      when(lock.tryLock(60, 600, TimeUnit.SECONDS)).thenThrow(new InterruptedException());

      assertThrows(LockNotAcquiredException.class, () -> aspect.handleDistributedLock(joinPoint));

      assertTrue(Thread.currentThread().isInterrupted());
      verify(joinPoint, never()).proceed();
      Thread.interrupted(); // Clear interrupt status for other tests
    }

    @Test
    @DisplayName("Should restore interrupt status after InterruptedException")
    void shouldRestoreInterruptStatus() throws Throwable {
      setupAnnotation(
          "test-lock", LockAcquisitionMode.WAIT_AND_SKIP, "", "", ReturnDefaultHandler.class);
      when(redissonClient.getLock("lock:test-lock")).thenReturn(lock);
      when(lock.tryLock(60, 600, TimeUnit.SECONDS)).thenThrow(new InterruptedException());

      aspect.handleDistributedLock(joinPoint);

      assertTrue(Thread.currentThread().isInterrupted());
      Thread.interrupted(); // Clear interrupt status for other tests
    }
  }

  @Nested
  @DisplayName("Lock Acquisition Tests - WAIT_AND_SKIP Mode")
  class WaitAndSkipModeTests {

    @Test
    @DisplayName("Should wait for lock and execute method when lock becomes available")
    void shouldWaitForLockAndExecuteMethod() throws Throwable {
      setupAnnotation(
          "test-lock", LockAcquisitionMode.WAIT_AND_SKIP, "", "", ThrowExceptionHandler.class);
      when(redissonClient.getLock("lock:test-lock")).thenReturn(lock);
      when(lock.tryLock(60, 600, TimeUnit.SECONDS)).thenReturn(true);
      when(lock.isHeldByCurrentThread()).thenReturn(true);
      when(joinPoint.proceed()).thenReturn("result");

      Object result = aspect.handleDistributedLock(joinPoint);

      assertEquals("result", result);
      verify(joinPoint).proceed();
      verify(lock).unlock();
    }

    @Test
    @DisplayName("Should skip execution and return null after wait timeout with RETURN_DEFAULT")
    void shouldSkipExecutionAfterWaitTimeout() throws Throwable {
      setupAnnotation(
          "test-lock", LockAcquisitionMode.WAIT_AND_SKIP, "", "", ReturnDefaultHandler.class);
      when(redissonClient.getLock("lock:test-lock")).thenReturn(lock);
      when(lock.tryLock(60, 600, TimeUnit.SECONDS)).thenReturn(false);

      Object result = aspect.handleDistributedLock(joinPoint);

      assertNull(result);
      verify(joinPoint, never()).proceed();
    }

    @Test
    @DisplayName("Should use configured wait time for WAIT_AND_SKIP mode")
    void shouldUseConfiguredWaitTime() throws Throwable {
      setupAnnotation(
          "test-lock", LockAcquisitionMode.WAIT_AND_SKIP, "", "", ThrowExceptionHandler.class);
      when(redissonClient.getLock("lock:test-lock")).thenReturn(lock);
      when(lock.tryLock(eq(60L), anyLong(), eq(TimeUnit.SECONDS))).thenReturn(true);
      when(lock.isHeldByCurrentThread()).thenReturn(true);

      aspect.handleDistributedLock(joinPoint);

      verify(lock).tryLock(eq(60L), anyLong(), eq(TimeUnit.SECONDS));
    }
  }

  @Nested
  @DisplayName("Lock Release Tests")
  class LockReleaseTests {

    @Test
    @DisplayName("Should release lock after successful method execution")
    void shouldReleaseLockAfterSuccessfulExecution() throws Throwable {
      setupAnnotation(
          "test-lock", LockAcquisitionMode.SKIP_IMMEDIATELY, "", "", ThrowExceptionHandler.class);
      when(redissonClient.getLock("lock:test-lock")).thenReturn(lock);
      when(lock.tryLock(0, 600, TimeUnit.SECONDS)).thenReturn(true);
      when(lock.isHeldByCurrentThread()).thenReturn(true);
      when(joinPoint.proceed()).thenReturn("result");

      aspect.handleDistributedLock(joinPoint);

      verify(lock).unlock();
    }

    @Test
    @DisplayName("Should release lock when method throws exception")
    void shouldReleaseLockWhenMethodThrowsException() throws Throwable {
      setupAnnotation(
          "test-lock", LockAcquisitionMode.SKIP_IMMEDIATELY, "", "", ThrowExceptionHandler.class);
      when(redissonClient.getLock("lock:test-lock")).thenReturn(lock);
      when(lock.tryLock(0, 600, TimeUnit.SECONDS)).thenReturn(true);
      when(lock.isHeldByCurrentThread()).thenReturn(true);
      when(joinPoint.proceed()).thenThrow(new RuntimeException("Test exception"));

      assertThrows(RuntimeException.class, () -> aspect.handleDistributedLock(joinPoint));

      verify(lock).unlock();
    }

    @Test
    @DisplayName("Should not release lock if not held by current thread")
    void shouldNotReleaseLockIfNotHeldByCurrentThread() throws Throwable {
      setupAnnotation(
          "test-lock", LockAcquisitionMode.SKIP_IMMEDIATELY, "", "", ThrowExceptionHandler.class);
      when(redissonClient.getLock("lock:test-lock")).thenReturn(lock);
      when(lock.tryLock(0, 600, TimeUnit.SECONDS)).thenReturn(true);
      when(lock.isHeldByCurrentThread()).thenReturn(false);
      when(joinPoint.proceed()).thenReturn("result");

      aspect.handleDistributedLock(joinPoint);

      verify(lock, never()).unlock();
    }

    @Test
    @DisplayName("Should handle IllegalMonitorStateException during unlock gracefully")
    void shouldHandleIllegalMonitorStateExceptionDuringUnlock() throws Throwable {
      setupAnnotation(
          "test-lock", LockAcquisitionMode.SKIP_IMMEDIATELY, "", "", ThrowExceptionHandler.class);
      when(redissonClient.getLock("lock:test-lock")).thenReturn(lock);
      when(lock.tryLock(0, 600, TimeUnit.SECONDS)).thenReturn(true);
      when(lock.isHeldByCurrentThread()).thenReturn(true);
      when(joinPoint.proceed()).thenReturn("result");
      doThrow(new IllegalMonitorStateException("Lock expired")).when(lock).unlock();

      Object result = aspect.handleDistributedLock(joinPoint);

      assertEquals("result", result);
    }
  }

  @Nested
  @DisplayName("Custom Configuration Tests")
  class CustomConfigurationTests {

    @Test
    @DisplayName("Should use custom lease time from annotation")
    void shouldUseCustomLeaseTimeFromAnnotation() throws Throwable {
      setupAnnotation(
          "test-lock", LockAcquisitionMode.SKIP_IMMEDIATELY, "5m", "", ThrowExceptionHandler.class);
      when(redissonClient.getLock("lock:test-lock")).thenReturn(lock);
      when(lock.tryLock(0, 300, TimeUnit.SECONDS)).thenReturn(true);
      when(lock.isHeldByCurrentThread()).thenReturn(true);

      aspect.handleDistributedLock(joinPoint);

      verify(lock).tryLock(0, 300, TimeUnit.SECONDS);
    }

    @Test
    @DisplayName("Should use custom wait time from annotation")
    void shouldUseCustomWaitTimeFromAnnotation() throws Throwable {
      setupAnnotation(
          "test-lock", LockAcquisitionMode.WAIT_AND_SKIP, "", "30s", ThrowExceptionHandler.class);
      when(redissonClient.getLock("lock:test-lock")).thenReturn(lock);
      when(lock.tryLock(30, 600, TimeUnit.SECONDS)).thenReturn(true);
      when(lock.isHeldByCurrentThread()).thenReturn(true);

      aspect.handleDistributedLock(joinPoint);

      verify(lock).tryLock(30, 600, TimeUnit.SECONDS);
    }
  }

  @Nested
  @DisplayName("Duration Parsing Tests")
  class DurationParsingTests {

    @Test
    @DisplayName("Should parse ISO-8601 format for lease time (PT5M)")
    void shouldParseIso8601FormatForLeaseTime() throws Throwable {
      setupAnnotation(
          "test-lock",
          LockAcquisitionMode.SKIP_IMMEDIATELY,
          "PT5M",
          "",
          ThrowExceptionHandler.class);
      when(redissonClient.getLock("lock:test-lock")).thenReturn(lock);
      when(lock.tryLock(0, 300, TimeUnit.SECONDS)).thenReturn(true);
      when(lock.isHeldByCurrentThread()).thenReturn(true);

      aspect.handleDistributedLock(joinPoint);

      verify(lock).tryLock(0, 300, TimeUnit.SECONDS);
    }

    @Test
    @DisplayName("Should parse ISO-8601 format for wait time (PT30S)")
    void shouldParseIso8601FormatForWaitTime() throws Throwable {
      setupAnnotation(
          "test-lock", LockAcquisitionMode.WAIT_AND_SKIP, "", "PT30S", ThrowExceptionHandler.class);
      when(redissonClient.getLock("lock:test-lock")).thenReturn(lock);
      when(lock.tryLock(30, 600, TimeUnit.SECONDS)).thenReturn(true);
      when(lock.isHeldByCurrentThread()).thenReturn(true);

      aspect.handleDistributedLock(joinPoint);

      verify(lock).tryLock(30, 600, TimeUnit.SECONDS);
    }

    @Test
    @DisplayName("Should parse simple format with seconds (45s)")
    void shouldParseSimpleFormatWithSeconds() throws Throwable {
      setupAnnotation(
          "test-lock", LockAcquisitionMode.WAIT_AND_SKIP, "", "45s", ThrowExceptionHandler.class);
      when(redissonClient.getLock("lock:test-lock")).thenReturn(lock);
      when(lock.tryLock(45, 600, TimeUnit.SECONDS)).thenReturn(true);
      when(lock.isHeldByCurrentThread()).thenReturn(true);

      aspect.handleDistributedLock(joinPoint);

      verify(lock).tryLock(45, 600, TimeUnit.SECONDS);
    }

    @Test
    @DisplayName("Should parse simple format with minutes (2m)")
    void shouldParseSimpleFormatWithMinutes() throws Throwable {
      setupAnnotation(
          "test-lock", LockAcquisitionMode.SKIP_IMMEDIATELY, "2m", "", ThrowExceptionHandler.class);
      when(redissonClient.getLock("lock:test-lock")).thenReturn(lock);
      when(lock.tryLock(0, 120, TimeUnit.SECONDS)).thenReturn(true);
      when(lock.isHeldByCurrentThread()).thenReturn(true);

      aspect.handleDistributedLock(joinPoint);

      verify(lock).tryLock(0, 120, TimeUnit.SECONDS);
    }

    @Test
    @DisplayName("Should parse simple format with hours (1h)")
    void shouldParseSimpleFormatWithHours() throws Throwable {
      setupAnnotation(
          "test-lock", LockAcquisitionMode.SKIP_IMMEDIATELY, "1h", "", ThrowExceptionHandler.class);
      when(redissonClient.getLock("lock:test-lock")).thenReturn(lock);
      when(lock.tryLock(0, 3600, TimeUnit.SECONDS)).thenReturn(true);
      when(lock.isHeldByCurrentThread()).thenReturn(true);

      aspect.handleDistributedLock(joinPoint);

      verify(lock).tryLock(0, 3600, TimeUnit.SECONDS);
    }

    @Test
    @DisplayName("Should parse ISO-8601 format with hours (PT1H)")
    void shouldParseIso8601FormatWithHours() throws Throwable {
      setupAnnotation(
          "test-lock",
          LockAcquisitionMode.SKIP_IMMEDIATELY,
          "PT1H",
          "",
          ThrowExceptionHandler.class);
      when(redissonClient.getLock("lock:test-lock")).thenReturn(lock);
      when(lock.tryLock(0, 3600, TimeUnit.SECONDS)).thenReturn(true);
      when(lock.isHeldByCurrentThread()).thenReturn(true);

      aspect.handleDistributedLock(joinPoint);

      verify(lock).tryLock(0, 3600, TimeUnit.SECONDS);
    }

    @Test
    @DisplayName("Should parse ISO-8601 combined format (PT1H30M)")
    void shouldParseIso8601CombinedFormat() throws Throwable {
      setupAnnotation(
          "test-lock",
          LockAcquisitionMode.SKIP_IMMEDIATELY,
          "PT1H30M",
          "",
          ThrowExceptionHandler.class);
      when(redissonClient.getLock("lock:test-lock")).thenReturn(lock);
      when(lock.tryLock(0, 5400, TimeUnit.SECONDS)).thenReturn(true);
      when(lock.isHeldByCurrentThread()).thenReturn(true);

      aspect.handleDistributedLock(joinPoint);

      verify(lock).tryLock(0, 5400, TimeUnit.SECONDS);
    }

    @Test
    @DisplayName("Should use default when duration is empty string")
    void shouldUseDefaultWhenDurationIsEmpty() throws Throwable {
      setupAnnotation(
          "test-lock", LockAcquisitionMode.SKIP_IMMEDIATELY, "", "", ThrowExceptionHandler.class);
      when(redissonClient.getLock("lock:test-lock")).thenReturn(lock);
      when(lock.tryLock(0, 600, TimeUnit.SECONDS)).thenReturn(true);
      when(lock.isHeldByCurrentThread()).thenReturn(true);

      aspect.handleDistributedLock(joinPoint);

      verify(lock).tryLock(0, 600, TimeUnit.SECONDS); // 10 minutes default
    }

    @Test
    @DisplayName("Should use default when duration is blank string")
    void shouldUseDefaultWhenDurationIsBlank() throws Throwable {
      setupAnnotation(
          "test-lock",
          LockAcquisitionMode.SKIP_IMMEDIATELY,
          "   ",
          "",
          ThrowExceptionHandler.class);
      when(redissonClient.getLock("lock:test-lock")).thenReturn(lock);
      when(lock.tryLock(0, 600, TimeUnit.SECONDS)).thenReturn(true);
      when(lock.isHeldByCurrentThread()).thenReturn(true);

      aspect.handleDistributedLock(joinPoint);

      verify(lock).tryLock(0, 600, TimeUnit.SECONDS); // 10 minutes default
    }
  }

  @Nested
  @DisplayName("Lock Key Tests")
  class LockKeyTests {

    @Test
    @DisplayName("Should use key prefix from properties")
    void shouldUseKeyPrefixFromProperties() throws Throwable {
      setupAnnotation(
          "my-task", LockAcquisitionMode.SKIP_IMMEDIATELY, "", "", ReturnDefaultHandler.class);
      when(redissonClient.getLock("lock:my-task")).thenReturn(lock);
      when(lock.tryLock(anyLong(), anyLong(), eq(TimeUnit.SECONDS))).thenReturn(false);

      aspect.handleDistributedLock(joinPoint);

      verify(redissonClient).getLock("lock:my-task");
    }

    @Test
    @DisplayName("Should throw IllegalArgumentException when key is empty")
    void shouldThrowIllegalArgumentExceptionWhenKeyIsEmpty() {
      setupAnnotation(
          "", LockAcquisitionMode.SKIP_IMMEDIATELY, "", "", ThrowExceptionHandler.class);

      IllegalArgumentException thrown =
          assertThrows(
              IllegalArgumentException.class, () -> aspect.handleDistributedLock(joinPoint));

      assertTrue(thrown.getMessage().contains("must not be blank"));
    }

    @Test
    @DisplayName("Should throw IllegalArgumentException when key is blank")
    void shouldThrowIllegalArgumentExceptionWhenKeyIsBlank() {
      setupAnnotation(
          "   ", LockAcquisitionMode.SKIP_IMMEDIATELY, "", "", ThrowExceptionHandler.class);

      IllegalArgumentException thrown =
          assertThrows(
              IllegalArgumentException.class, () -> aspect.handleDistributedLock(joinPoint));

      assertTrue(thrown.getMessage().contains("must not be blank"));
    }
  }

  @Nested
  @DisplayName("Skip Behavior Tests")
  class SkipBehaviorTests {

    @Test
    @DisplayName("Should throw LockNotAcquiredException when lock fails with THROW_EXCEPTION")
    void shouldThrowLockNotAcquiredExceptionWhenLockFails() throws Throwable {
      setupAnnotation(
          "test-lock", LockAcquisitionMode.SKIP_IMMEDIATELY, "", "", ThrowExceptionHandler.class);
      when(redissonClient.getLock("lock:test-lock")).thenReturn(lock);
      when(lock.tryLock(0, 600, TimeUnit.SECONDS)).thenReturn(false);

      LockNotAcquiredException thrown =
          assertThrows(
              LockNotAcquiredException.class, () -> aspect.handleDistributedLock(joinPoint));

      assertTrue(thrown.getMessage().contains("lock:test-lock"));
      assertEquals("lock:test-lock", thrown.getLockKey());
    }

    @Test
    @DisplayName("Should return false for boolean primitive with RETURN_DEFAULT")
    void shouldReturnFalseForBooleanPrimitive() throws Throwable {
      when(methodSignature.getReturnType()).thenReturn(boolean.class);
      setupAnnotation(
          "test-lock", LockAcquisitionMode.SKIP_IMMEDIATELY, "", "", ReturnDefaultHandler.class);
      when(redissonClient.getLock("lock:test-lock")).thenReturn(lock);
      when(lock.tryLock(0, 600, TimeUnit.SECONDS)).thenReturn(false);

      Object result = aspect.handleDistributedLock(joinPoint);

      assertEquals(false, result);
    }

    @Test
    @DisplayName("Should return 0 for int primitive with RETURN_DEFAULT")
    void shouldReturnZeroForIntPrimitive() throws Throwable {
      when(methodSignature.getReturnType()).thenReturn(int.class);
      setupAnnotation(
          "test-lock", LockAcquisitionMode.SKIP_IMMEDIATELY, "", "", ReturnDefaultHandler.class);
      when(redissonClient.getLock("lock:test-lock")).thenReturn(lock);
      when(lock.tryLock(0, 600, TimeUnit.SECONDS)).thenReturn(false);

      Object result = aspect.handleDistributedLock(joinPoint);

      assertEquals(0, result);
    }

    @Test
    @DisplayName("Should return null for void methods with RETURN_DEFAULT")
    void shouldReturnNullForVoidMethods() throws Throwable {
      when(methodSignature.getReturnType()).thenReturn(void.class);
      setupAnnotation(
          "test-lock", LockAcquisitionMode.SKIP_IMMEDIATELY, "", "", ReturnDefaultHandler.class);
      when(redissonClient.getLock("lock:test-lock")).thenReturn(lock);
      when(lock.tryLock(0, 600, TimeUnit.SECONDS)).thenReturn(false);

      Object result = aspect.handleDistributedLock(joinPoint);

      assertNull(result);
    }

    @Test
    @DisplayName("Should return 0L for long primitive with RETURN_DEFAULT")
    void shouldReturnZeroForLongPrimitive() throws Throwable {
      when(methodSignature.getReturnType()).thenReturn(long.class);
      setupAnnotation(
          "test-lock", LockAcquisitionMode.SKIP_IMMEDIATELY, "", "", ReturnDefaultHandler.class);
      when(redissonClient.getLock("lock:test-lock")).thenReturn(lock);
      when(lock.tryLock(0, 600, TimeUnit.SECONDS)).thenReturn(false);

      Object result = aspect.handleDistributedLock(joinPoint);

      assertEquals(0L, result);
    }

    @Test
    @DisplayName("Should return 0.0d for double primitive with RETURN_DEFAULT")
    void shouldReturnZeroForDoublePrimitive() throws Throwable {
      when(methodSignature.getReturnType()).thenReturn(double.class);
      setupAnnotation(
          "test-lock", LockAcquisitionMode.SKIP_IMMEDIATELY, "", "", ReturnDefaultHandler.class);
      when(redissonClient.getLock("lock:test-lock")).thenReturn(lock);
      when(lock.tryLock(0, 600, TimeUnit.SECONDS)).thenReturn(false);

      Object result = aspect.handleDistributedLock(joinPoint);

      assertEquals(0.0d, result);
    }

    @Test
    @DisplayName("Should return 0.0f for float primitive with RETURN_DEFAULT")
    void shouldReturnZeroForFloatPrimitive() throws Throwable {
      when(methodSignature.getReturnType()).thenReturn(float.class);
      setupAnnotation(
          "test-lock", LockAcquisitionMode.SKIP_IMMEDIATELY, "", "", ReturnDefaultHandler.class);
      when(redissonClient.getLock("lock:test-lock")).thenReturn(lock);
      when(lock.tryLock(0, 600, TimeUnit.SECONDS)).thenReturn(false);

      Object result = aspect.handleDistributedLock(joinPoint);

      assertEquals(0.0f, result);
    }

    @Test
    @DisplayName("Should return 0 for byte primitive with RETURN_DEFAULT")
    void shouldReturnZeroForBytePrimitive() throws Throwable {
      when(methodSignature.getReturnType()).thenReturn(byte.class);
      setupAnnotation(
          "test-lock", LockAcquisitionMode.SKIP_IMMEDIATELY, "", "", ReturnDefaultHandler.class);
      when(redissonClient.getLock("lock:test-lock")).thenReturn(lock);
      when(lock.tryLock(0, 600, TimeUnit.SECONDS)).thenReturn(false);

      Object result = aspect.handleDistributedLock(joinPoint);

      assertEquals((byte) 0, result);
    }

    @Test
    @DisplayName("Should return 0 for short primitive with RETURN_DEFAULT")
    void shouldReturnZeroForShortPrimitive() throws Throwable {
      when(methodSignature.getReturnType()).thenReturn(short.class);
      setupAnnotation(
          "test-lock", LockAcquisitionMode.SKIP_IMMEDIATELY, "", "", ReturnDefaultHandler.class);
      when(redissonClient.getLock("lock:test-lock")).thenReturn(lock);
      when(lock.tryLock(0, 600, TimeUnit.SECONDS)).thenReturn(false);

      Object result = aspect.handleDistributedLock(joinPoint);

      assertEquals((short) 0, result);
    }

    @Test
    @DisplayName("Should return null char for char primitive with RETURN_DEFAULT")
    void shouldReturnNullCharForCharPrimitive() throws Throwable {
      when(methodSignature.getReturnType()).thenReturn(char.class);
      setupAnnotation(
          "test-lock", LockAcquisitionMode.SKIP_IMMEDIATELY, "", "", ReturnDefaultHandler.class);
      when(redissonClient.getLock("lock:test-lock")).thenReturn(lock);
      when(lock.tryLock(0, 600, TimeUnit.SECONDS)).thenReturn(false);

      Object result = aspect.handleDistributedLock(joinPoint);

      assertEquals('\u0000', result);
    }

    @Test
    @DisplayName("Should return null for Object return type with RETURN_DEFAULT")
    void shouldReturnNullForObjectReturnType() throws Throwable {
      when(methodSignature.getReturnType()).thenReturn(String.class);
      setupAnnotation(
          "test-lock", LockAcquisitionMode.SKIP_IMMEDIATELY, "", "", ReturnDefaultHandler.class);
      when(redissonClient.getLock("lock:test-lock")).thenReturn(lock);
      when(lock.tryLock(0, 600, TimeUnit.SECONDS)).thenReturn(false);

      Object result = aspect.handleDistributedLock(joinPoint);

      assertNull(result);
    }

    @Test
    @DisplayName("Should return false for Boolean wrapper with RETURN_DEFAULT")
    void shouldReturnFalseForBooleanWrapper() throws Throwable {
      when(methodSignature.getReturnType()).thenReturn(Boolean.class);
      setupAnnotation(
          "test-lock", LockAcquisitionMode.SKIP_IMMEDIATELY, "", "", ReturnDefaultHandler.class);
      when(redissonClient.getLock("lock:test-lock")).thenReturn(lock);
      when(lock.tryLock(0, 600, TimeUnit.SECONDS)).thenReturn(false);

      Object result = aspect.handleDistributedLock(joinPoint);

      assertEquals(false, result);
    }

    @Test
    @DisplayName("Should return 0 for Integer wrapper with RETURN_DEFAULT")
    void shouldReturnZeroForIntegerWrapper() throws Throwable {
      when(methodSignature.getReturnType()).thenReturn(Integer.class);
      setupAnnotation(
          "test-lock", LockAcquisitionMode.SKIP_IMMEDIATELY, "", "", ReturnDefaultHandler.class);
      when(redissonClient.getLock("lock:test-lock")).thenReturn(lock);
      when(lock.tryLock(0, 600, TimeUnit.SECONDS)).thenReturn(false);

      Object result = aspect.handleDistributedLock(joinPoint);

      assertEquals(0, result);
    }

    @Test
    @DisplayName("Should return 0L for Long wrapper with RETURN_DEFAULT")
    void shouldReturnZeroForLongWrapper() throws Throwable {
      when(methodSignature.getReturnType()).thenReturn(Long.class);
      setupAnnotation(
          "test-lock", LockAcquisitionMode.SKIP_IMMEDIATELY, "", "", ReturnDefaultHandler.class);
      when(redissonClient.getLock("lock:test-lock")).thenReturn(lock);
      when(lock.tryLock(0, 600, TimeUnit.SECONDS)).thenReturn(false);

      Object result = aspect.handleDistributedLock(joinPoint);

      assertEquals(0L, result);
    }

    @Test
    @DisplayName("Should return 0.0d for Double wrapper with RETURN_DEFAULT")
    void shouldReturnZeroForDoubleWrapper() throws Throwable {
      when(methodSignature.getReturnType()).thenReturn(Double.class);
      setupAnnotation(
          "test-lock", LockAcquisitionMode.SKIP_IMMEDIATELY, "", "", ReturnDefaultHandler.class);
      when(redissonClient.getLock("lock:test-lock")).thenReturn(lock);
      when(lock.tryLock(0, 600, TimeUnit.SECONDS)).thenReturn(false);

      Object result = aspect.handleDistributedLock(joinPoint);

      assertEquals(0.0d, result);
    }

    @Test
    @DisplayName("Should return 0.0f for Float wrapper with RETURN_DEFAULT")
    void shouldReturnZeroForFloatWrapper() throws Throwable {
      when(methodSignature.getReturnType()).thenReturn(Float.class);
      setupAnnotation(
          "test-lock", LockAcquisitionMode.SKIP_IMMEDIATELY, "", "", ReturnDefaultHandler.class);
      when(redissonClient.getLock("lock:test-lock")).thenReturn(lock);
      when(lock.tryLock(0, 600, TimeUnit.SECONDS)).thenReturn(false);

      Object result = aspect.handleDistributedLock(joinPoint);

      assertEquals(0.0f, result);
    }

    @Test
    @DisplayName("Should return 0 for Byte wrapper with RETURN_DEFAULT")
    void shouldReturnZeroForByteWrapper() throws Throwable {
      when(methodSignature.getReturnType()).thenReturn(Byte.class);
      setupAnnotation(
          "test-lock", LockAcquisitionMode.SKIP_IMMEDIATELY, "", "", ReturnDefaultHandler.class);
      when(redissonClient.getLock("lock:test-lock")).thenReturn(lock);
      when(lock.tryLock(0, 600, TimeUnit.SECONDS)).thenReturn(false);

      Object result = aspect.handleDistributedLock(joinPoint);

      assertEquals((byte) 0, result);
    }

    @Test
    @DisplayName("Should return 0 for Short wrapper with RETURN_DEFAULT")
    void shouldReturnZeroForShortWrapper() throws Throwable {
      when(methodSignature.getReturnType()).thenReturn(Short.class);
      setupAnnotation(
          "test-lock", LockAcquisitionMode.SKIP_IMMEDIATELY, "", "", ReturnDefaultHandler.class);
      when(redissonClient.getLock("lock:test-lock")).thenReturn(lock);
      when(lock.tryLock(0, 600, TimeUnit.SECONDS)).thenReturn(false);

      Object result = aspect.handleDistributedLock(joinPoint);

      assertEquals((short) 0, result);
    }

    @Test
    @DisplayName("Should return null char for Character wrapper with RETURN_DEFAULT")
    void shouldReturnNullCharForCharacterWrapper() throws Throwable {
      when(methodSignature.getReturnType()).thenReturn(Character.class);
      setupAnnotation(
          "test-lock", LockAcquisitionMode.SKIP_IMMEDIATELY, "", "", ReturnDefaultHandler.class);
      when(redissonClient.getLock("lock:test-lock")).thenReturn(lock);
      when(lock.tryLock(0, 600, TimeUnit.SECONDS)).thenReturn(false);

      Object result = aspect.handleDistributedLock(joinPoint);

      assertEquals('\u0000', result);
    }
  }

  @Nested
  @DisplayName("Configuration Validation Tests")
  class ConfigurationValidationTests {

    @Nested
    @DisplayName("leaseTime property tests")
    class LeaseTimeTests {

      @Test
      @DisplayName("Should use default lease time when null")
      void shouldUseDefaultLeaseTimeWhenNull() {
        LocksmithProperties props = new LocksmithProperties(null, Duration.ofSeconds(60), "lock:");

        assertEquals(Duration.ofMinutes(10), props.leaseTime());
      }

      @Test
      @DisplayName("Should use default lease time when zero")
      void shouldUseDefaultLeaseTimeWhenZero() {
        LocksmithProperties props =
            new LocksmithProperties(Duration.ZERO, Duration.ofSeconds(60), "lock:");

        assertEquals(Duration.ofMinutes(10), props.leaseTime());
      }

      @Test
      @DisplayName("Should use default lease time when negative")
      void shouldUseDefaultLeaseTimeWhenNegative() {
        LocksmithProperties props =
            new LocksmithProperties(Duration.ofMinutes(-5), Duration.ofSeconds(60), "lock:");

        assertEquals(Duration.ofMinutes(10), props.leaseTime());
      }

      @Test
      @DisplayName("Should use custom lease time when positive")
      void shouldUseCustomLeaseTimeWhenPositive() {
        LocksmithProperties props =
            new LocksmithProperties(Duration.ofMinutes(15), Duration.ofSeconds(60), "lock:");

        assertEquals(Duration.ofMinutes(15), props.leaseTime());
      }

      @Test
      @DisplayName("Should accept lease time in seconds")
      void shouldAcceptLeaseTimeInSeconds() {
        LocksmithProperties props =
            new LocksmithProperties(Duration.ofSeconds(300), Duration.ofSeconds(60), "lock:");

        assertEquals(Duration.ofSeconds(300), props.leaseTime());
      }

      @Test
      @DisplayName("Should accept lease time in hours")
      void shouldAcceptLeaseTimeInHours() {
        LocksmithProperties props =
            new LocksmithProperties(Duration.ofHours(1), Duration.ofSeconds(60), "lock:");

        assertEquals(Duration.ofHours(1), props.leaseTime());
      }

      @Test
      @DisplayName("Should accept lease time in millis")
      void shouldAcceptLeaseTimeInMillis() {
        LocksmithProperties props =
            new LocksmithProperties(Duration.ofMillis(5000), Duration.ofSeconds(60), "lock:");

        assertEquals(Duration.ofMillis(5000), props.leaseTime());
      }
    }

    @Nested
    @DisplayName("waitTime property tests")
    class WaitTimeTests {

      @Test
      @DisplayName("Should use default wait time when null")
      void shouldUseDefaultWaitTimeWhenNull() {
        LocksmithProperties props = new LocksmithProperties(Duration.ofMinutes(10), null, "lock:");

        assertEquals(Duration.ofSeconds(60), props.waitTime());
      }

      @Test
      @DisplayName("Should use default wait time when negative")
      void shouldUseDefaultWaitTimeWhenNegative() {
        LocksmithProperties props =
            new LocksmithProperties(Duration.ofMinutes(10), Duration.ofSeconds(-30), "lock:");

        assertEquals(Duration.ofSeconds(60), props.waitTime());
      }

      @Test
      @DisplayName("Should allow zero wait time")
      void shouldAllowZeroWaitTime() {
        LocksmithProperties props =
            new LocksmithProperties(Duration.ofMinutes(10), Duration.ZERO, "lock:");

        assertEquals(Duration.ZERO, props.waitTime());
      }

      @Test
      @DisplayName("Should use custom wait time when positive")
      void shouldUseCustomWaitTimeWhenPositive() {
        LocksmithProperties props =
            new LocksmithProperties(Duration.ofMinutes(10), Duration.ofSeconds(90), "lock:");

        assertEquals(Duration.ofSeconds(90), props.waitTime());
      }

      @Test
      @DisplayName("Should accept wait time in minutes")
      void shouldAcceptWaitTimeInMinutes() {
        LocksmithProperties props =
            new LocksmithProperties(Duration.ofMinutes(10), Duration.ofMinutes(2), "lock:");

        assertEquals(Duration.ofMinutes(2), props.waitTime());
      }

      @Test
      @DisplayName("Should accept wait time in millis")
      void shouldAcceptWaitTimeInMillis() {
        LocksmithProperties props =
            new LocksmithProperties(Duration.ofMinutes(10), Duration.ofMillis(500), "lock:");

        assertEquals(Duration.ofMillis(500), props.waitTime());
      }
    }

    @Nested
    @DisplayName("keyPrefix property tests")
    class KeyPrefixTests {

      @Test
      @DisplayName("Should use default key prefix when null")
      void shouldUseDefaultKeyPrefixWhenNull() {
        LocksmithProperties props =
            new LocksmithProperties(Duration.ofMinutes(10), Duration.ofSeconds(60), null);

        assertEquals("lock:", props.keyPrefix());
      }

      @Test
      @DisplayName("Should use default key prefix when empty")
      void shouldUseDefaultKeyPrefixWhenEmpty() {
        LocksmithProperties props =
            new LocksmithProperties(Duration.ofMinutes(10), Duration.ofSeconds(60), "");

        assertEquals("lock:", props.keyPrefix());
      }

      @Test
      @DisplayName("Should use default key prefix when blank with spaces")
      void shouldUseDefaultKeyPrefixWhenBlankWithSpaces() {
        LocksmithProperties props =
            new LocksmithProperties(Duration.ofMinutes(10), Duration.ofSeconds(60), "   ");

        assertEquals("lock:", props.keyPrefix());
      }

      @Test
      @DisplayName("Should use default key prefix when blank with tabs")
      void shouldUseDefaultKeyPrefixWhenBlankWithTabs() {
        LocksmithProperties props =
            new LocksmithProperties(Duration.ofMinutes(10), Duration.ofSeconds(60), "\t\t");

        assertEquals("lock:", props.keyPrefix());
      }

      @Test
      @DisplayName("Should use custom key prefix when valid")
      void shouldUseCustomKeyPrefixWhenValid() {
        LocksmithProperties props =
            new LocksmithProperties(Duration.ofMinutes(10), Duration.ofSeconds(60), "myapp:");

        assertEquals("myapp:", props.keyPrefix());
      }

      @Test
      @DisplayName("Should use custom key prefix without colon")
      void shouldUseCustomKeyPrefixWithoutColon() {
        LocksmithProperties props =
            new LocksmithProperties(
                Duration.ofMinutes(10), Duration.ofSeconds(60), "distributed-lock");

        assertEquals("distributed-lock", props.keyPrefix());
      }

      @Test
      @DisplayName("Should preserve key prefix with special characters")
      void shouldPreserveKeyPrefixWithSpecialCharacters() {
        LocksmithProperties props =
            new LocksmithProperties(
                Duration.ofMinutes(10), Duration.ofSeconds(60), "app:env:lock:");

        assertEquals("app:env:lock:", props.keyPrefix());
      }
    }

    @Nested
    @DisplayName("defaults() factory method tests")
    class DefaultsFactoryMethodTests {

      @Test
      @DisplayName("Should create instance with all default values")
      void shouldCreateInstanceWithAllDefaultValues() {
        LocksmithProperties props = LocksmithProperties.defaults();

        assertEquals(Duration.ofMinutes(10), props.leaseTime());
        assertEquals(Duration.ofSeconds(60), props.waitTime());
        assertEquals("lock:", props.keyPrefix());
      }

      @Test
      @DisplayName("Should match default constants")
      void shouldMatchDefaultConstants() {
        LocksmithProperties props = LocksmithProperties.defaults();

        assertEquals(LocksmithProperties.DEFAULT_LEASE_TIME, props.leaseTime());
        assertEquals(LocksmithProperties.DEFAULT_WAIT_TIME, props.waitTime());
        assertEquals(LocksmithProperties.DEFAULT_KEY_PREFIX, props.keyPrefix());
      }
    }

    @Nested
    @DisplayName("All null parameters tests")
    class AllNullParametersTests {

      @Test
      @DisplayName("Should use all defaults when all parameters are null")
      void shouldUseAllDefaultsWhenAllParametersAreNull() {
        LocksmithProperties props = new LocksmithProperties(null, null, null);

        assertEquals(Duration.ofMinutes(10), props.leaseTime());
        assertEquals(Duration.ofSeconds(60), props.waitTime());
        assertEquals("lock:", props.keyPrefix());
      }
    }

    @Nested
    @DisplayName("Custom values tests")
    class CustomValuesTests {

      @Test
      @DisplayName("Should use all custom values when valid")
      void shouldUseAllCustomValuesWhenValid() {
        LocksmithProperties props =
            new LocksmithProperties(Duration.ofMinutes(15), Duration.ofSeconds(90), "mylock:");

        assertEquals(Duration.ofMinutes(15), props.leaseTime());
        assertEquals(Duration.ofSeconds(90), props.waitTime());
        assertEquals("mylock:", props.keyPrefix());
      }

      @Test
      @DisplayName("Should handle mixed valid and invalid values")
      void shouldHandleMixedValidAndInvalidValues() {
        LocksmithProperties props =
            new LocksmithProperties(Duration.ofMinutes(-5), Duration.ofSeconds(30), null);

        assertEquals(Duration.ofMinutes(10), props.leaseTime());
        assertEquals(Duration.ofSeconds(30), props.waitTime());
        assertEquals("lock:", props.keyPrefix());
      }

      @Test
      @DisplayName("Should handle very large duration values")
      void shouldHandleVeryLargeDurationValues() {
        LocksmithProperties props =
            new LocksmithProperties(Duration.ofDays(1), Duration.ofHours(2), "biglock:");

        assertEquals(Duration.ofDays(1), props.leaseTime());
        assertEquals(Duration.ofHours(2), props.waitTime());
        assertEquals("biglock:", props.keyPrefix());
      }

      @Test
      @DisplayName("Should handle very small positive duration values")
      void shouldHandleVerySmallPositiveDurationValues() {
        LocksmithProperties props =
            new LocksmithProperties(Duration.ofNanos(1000000), Duration.ofMillis(1), "smalllock:");

        assertEquals(Duration.ofNanos(1000000), props.leaseTime());
        assertEquals(Duration.ofMillis(1), props.waitTime());
        assertEquals("smalllock:", props.keyPrefix());
      }
    }
  }

  @Nested
  @DisplayName("SpEL Key Resolution Tests")
  class SpelKeyResolutionTests {

    @Test
    @DisplayName("Should resolve SpEL expression with method parameter")
    void shouldResolveSpelExpressionWithMethodParameter() throws Throwable {
      Method method = SpelTestClass.class.getMethod("processUser", String.class);
      when(methodSignature.getMethod()).thenReturn(method);
      when(methodSignature.getDeclaringType()).thenReturn(SpelTestClass.class);
      when(methodSignature.getName()).thenReturn("processUser");
      when(joinPoint.getArgs()).thenReturn(new Object[] {"user123"});

      when(redissonClient.getLock("lock:user123")).thenReturn(lock);
      when(lock.tryLock(0, 600, TimeUnit.SECONDS)).thenReturn(true);
      when(lock.isHeldByCurrentThread()).thenReturn(true);
      when(joinPoint.proceed()).thenReturn("result");

      Object result = aspect.handleDistributedLock(joinPoint);

      assertEquals("result", result);
      verify(redissonClient).getLock("lock:user123");
    }

    @Test
    @DisplayName("Should resolve SpEL expression with concatenation")
    void shouldResolveSpelExpressionWithConcatenation() throws Throwable {
      Method method = SpelTestClass.class.getMethod("processWithPrefix", Long.class);
      when(methodSignature.getMethod()).thenReturn(method);
      when(methodSignature.getDeclaringType()).thenReturn(SpelTestClass.class);
      when(methodSignature.getName()).thenReturn("processWithPrefix");
      when(joinPoint.getArgs()).thenReturn(new Object[] {42L});

      when(redissonClient.getLock("lock:user-42")).thenReturn(lock);
      when(lock.tryLock(0, 600, TimeUnit.SECONDS)).thenReturn(true);
      when(lock.isHeldByCurrentThread()).thenReturn(true);
      when(joinPoint.proceed()).thenReturn("result");

      Object result = aspect.handleDistributedLock(joinPoint);

      assertEquals("result", result);
      verify(redissonClient).getLock("lock:user-42");
    }

    @Test
    @DisplayName("Should use literal key when no SpEL expression present")
    void shouldUseLiteralKeyWhenNoSpelExpression() throws Throwable {
      Method method = SpelTestClass.class.getMethod("staticKeyMethod");
      when(methodSignature.getMethod()).thenReturn(method);
      when(methodSignature.getDeclaringType()).thenReturn(SpelTestClass.class);
      when(methodSignature.getName()).thenReturn("staticKeyMethod");
      when(joinPoint.getArgs()).thenReturn(new Object[] {});

      when(redissonClient.getLock("lock:static-key")).thenReturn(lock);
      when(lock.tryLock(0, 600, TimeUnit.SECONDS)).thenReturn(true);
      when(lock.isHeldByCurrentThread()).thenReturn(true);
      when(joinPoint.proceed()).thenReturn("result");

      Object result = aspect.handleDistributedLock(joinPoint);

      assertEquals("result", result);
      verify(redissonClient).getLock("lock:static-key");
    }

    @Test
    @DisplayName("Should throw exception when SpEL evaluates to null")
    void shouldThrowExceptionWhenSpelEvaluatesToNull() throws Throwable {
      Method method = SpelTestClass.class.getMethod("processUser", String.class);
      when(methodSignature.getMethod()).thenReturn(method);
      when(methodSignature.getDeclaringType()).thenReturn(SpelTestClass.class);
      when(methodSignature.getName()).thenReturn("processUser");
      when(joinPoint.getArgs()).thenReturn(new Object[] {null});

      IllegalArgumentException thrown =
          assertThrows(
              IllegalArgumentException.class, () -> aspect.handleDistributedLock(joinPoint));

      assertTrue(thrown.getMessage().contains("evaluated to null"));
    }

    @Test
    @DisplayName("Should throw exception when SpEL evaluates to blank string")
    void shouldThrowExceptionWhenSpelEvaluatesToBlank() throws Throwable {
      Method method = SpelTestClass.class.getMethod("processWithBlankValue", String.class);
      when(methodSignature.getMethod()).thenReturn(method);
      when(methodSignature.getDeclaringType()).thenReturn(SpelTestClass.class);
      when(methodSignature.getName()).thenReturn("processWithBlankValue");
      when(joinPoint.getArgs()).thenReturn(new Object[] {"   "});

      IllegalArgumentException thrown =
          assertThrows(
              IllegalArgumentException.class, () -> aspect.handleDistributedLock(joinPoint));

      assertTrue(thrown.getMessage().contains("evaluated to blank"));
    }

    @Test
    @DisplayName("Should resolve SpEL expression with object property")
    void shouldResolveSpelExpressionWithObjectProperty() throws Throwable {
      Method method = SpelTestClass.class.getMethod("updateUser", SpelTestClass.TestUser.class);
      when(methodSignature.getMethod()).thenReturn(method);
      when(methodSignature.getDeclaringType()).thenReturn(SpelTestClass.class);
      when(methodSignature.getName()).thenReturn("updateUser");
      when(joinPoint.getArgs())
          .thenReturn(new Object[] {new SpelTestClass.TestUser("user456", "John")});

      when(redissonClient.getLock("lock:user456")).thenReturn(lock);
      when(lock.tryLock(0, 600, TimeUnit.SECONDS)).thenReturn(true);
      when(lock.isHeldByCurrentThread()).thenReturn(true);
      when(joinPoint.proceed()).thenReturn("result");

      Object result = aspect.handleDistributedLock(joinPoint);

      assertEquals("result", result);
      verify(redissonClient).getLock("lock:user456");
    }

    @Test
    @DisplayName("Should throw exception when SpEL object property is null")
    void shouldThrowExceptionWhenSpelObjectPropertyIsNull() throws Throwable {
      Method method =
          SpelTestClass.class.getMethod("processUserName", SpelTestClass.TestUser.class);
      when(methodSignature.getMethod()).thenReturn(method);
      when(methodSignature.getDeclaringType()).thenReturn(SpelTestClass.class);
      when(methodSignature.getName()).thenReturn("processUserName");
      when(joinPoint.getArgs()).thenReturn(new Object[] {new SpelTestClass.TestUser("user456")});

      IllegalArgumentException thrown =
          assertThrows(
              IllegalArgumentException.class, () -> aspect.handleDistributedLock(joinPoint));

      assertTrue(thrown.getMessage().contains("evaluated to null"));
    }

    @Test
    @DisplayName("Should throw exception when SpEL evaluates to empty string")
    void shouldThrowExceptionWhenSpelEvaluatesToEmpty() throws Throwable {
      Method method = SpelTestClass.class.getMethod("processWithBlankValue", String.class);
      when(methodSignature.getMethod()).thenReturn(method);
      when(methodSignature.getDeclaringType()).thenReturn(SpelTestClass.class);
      when(methodSignature.getName()).thenReturn("processWithBlankValue");
      when(joinPoint.getArgs()).thenReturn(new Object[] {""});

      IllegalArgumentException thrown =
          assertThrows(
              IllegalArgumentException.class, () -> aspect.handleDistributedLock(joinPoint));

      assertTrue(thrown.getMessage().contains("evaluated to blank"));
    }
  }

  @Nested
  @DisplayName("Lock Type Tests")
  class LockTypeTests {

    private RReadWriteLock readWriteLock;
    private RLock readLock;
    private RLock writeLock;

    @BeforeEach
    void setUpReadWriteLock() {
      readWriteLock = mock(RReadWriteLock.class);
      readLock = mock(RLock.class);
      writeLock = mock(RLock.class);
      when(redissonClient.getReadWriteLock("lock:test-lock")).thenReturn(readWriteLock);
      when(readWriteLock.readLock()).thenReturn(readLock);
      when(readWriteLock.writeLock()).thenReturn(writeLock);
    }

    @Test
    @DisplayName("Should use reentrant lock for REENTRANT type")
    void shouldUseReentrantLockForReentrantType() throws Throwable {
      setupAnnotation(
          "test-lock",
          LockAcquisitionMode.SKIP_IMMEDIATELY,
          "",
          "",
          ThrowExceptionHandler.class,
          LockType.REENTRANT);
      when(redissonClient.getLock("lock:test-lock")).thenReturn(lock);
      when(lock.tryLock(0, 600, TimeUnit.SECONDS)).thenReturn(true);
      when(lock.isHeldByCurrentThread()).thenReturn(true);
      when(joinPoint.proceed()).thenReturn("result");

      Object result = aspect.handleDistributedLock(joinPoint);

      assertEquals("result", result);
      verify(redissonClient).getLock("lock:test-lock");
      verify(redissonClient, never()).getReadWriteLock(anyString());
    }

    @Test
    @DisplayName("Should use read lock for READ type")
    void shouldUseReadLockForReadType() throws Throwable {
      setupAnnotation(
          "test-lock",
          LockAcquisitionMode.SKIP_IMMEDIATELY,
          "",
          "",
          ThrowExceptionHandler.class,
          LockType.READ);
      when(readLock.tryLock(0, 600, TimeUnit.SECONDS)).thenReturn(true);
      when(readLock.isHeldByCurrentThread()).thenReturn(true);
      when(joinPoint.proceed()).thenReturn("result");

      Object result = aspect.handleDistributedLock(joinPoint);

      assertEquals("result", result);
      verify(redissonClient).getReadWriteLock("lock:test-lock");
      verify(readWriteLock).readLock();
      verify(readWriteLock, never()).writeLock();
      verify(redissonClient, never()).getLock(anyString());
    }

    @Test
    @DisplayName("Should use write lock for WRITE type")
    void shouldUseWriteLockForWriteType() throws Throwable {
      setupAnnotation(
          "test-lock",
          LockAcquisitionMode.SKIP_IMMEDIATELY,
          "",
          "",
          ThrowExceptionHandler.class,
          LockType.WRITE);
      when(writeLock.tryLock(0, 600, TimeUnit.SECONDS)).thenReturn(true);
      when(writeLock.isHeldByCurrentThread()).thenReturn(true);
      when(joinPoint.proceed()).thenReturn("result");

      Object result = aspect.handleDistributedLock(joinPoint);

      assertEquals("result", result);
      verify(redissonClient).getReadWriteLock("lock:test-lock");
      verify(readWriteLock).writeLock();
      verify(readWriteLock, never()).readLock();
      verify(redissonClient, never()).getLock(anyString());
    }

    @Test
    @DisplayName("Should release read lock after execution")
    void shouldReleaseReadLockAfterExecution() throws Throwable {
      setupAnnotation(
          "test-lock",
          LockAcquisitionMode.SKIP_IMMEDIATELY,
          "",
          "",
          ThrowExceptionHandler.class,
          LockType.READ);
      when(readLock.tryLock(0, 600, TimeUnit.SECONDS)).thenReturn(true);
      when(readLock.isHeldByCurrentThread()).thenReturn(true);
      when(joinPoint.proceed()).thenReturn("result");

      aspect.handleDistributedLock(joinPoint);

      verify(readLock).unlock();
    }

    @Test
    @DisplayName("Should release write lock after execution")
    void shouldReleaseWriteLockAfterExecution() throws Throwable {
      setupAnnotation(
          "test-lock",
          LockAcquisitionMode.SKIP_IMMEDIATELY,
          "",
          "",
          ThrowExceptionHandler.class,
          LockType.WRITE);
      when(writeLock.tryLock(0, 600, TimeUnit.SECONDS)).thenReturn(true);
      when(writeLock.isHeldByCurrentThread()).thenReturn(true);
      when(joinPoint.proceed()).thenReturn("result");

      aspect.handleDistributedLock(joinPoint);

      verify(writeLock).unlock();
    }

    @Test
    @DisplayName("Should skip and return default when read lock not acquired")
    void shouldSkipWhenReadLockNotAcquired() throws Throwable {
      setupAnnotation(
          "test-lock",
          LockAcquisitionMode.SKIP_IMMEDIATELY,
          "",
          "",
          ReturnDefaultHandler.class,
          LockType.READ);
      when(readLock.tryLock(0, 600, TimeUnit.SECONDS)).thenReturn(false);

      Object result = aspect.handleDistributedLock(joinPoint);

      assertNull(result);
      verify(joinPoint, never()).proceed();
    }

    @Test
    @DisplayName("Should throw exception when write lock not acquired with THROW_EXCEPTION")
    void shouldThrowExceptionWhenWriteLockNotAcquired() throws Throwable {
      setupAnnotation(
          "test-lock",
          LockAcquisitionMode.SKIP_IMMEDIATELY,
          "",
          "",
          ThrowExceptionHandler.class,
          LockType.WRITE);
      when(writeLock.tryLock(0, 600, TimeUnit.SECONDS)).thenReturn(false);

      assertThrows(LockNotAcquiredException.class, () -> aspect.handleDistributedLock(joinPoint));
    }

    @Test
    @DisplayName("Should use custom lease time with read lock")
    void shouldUseCustomLeaseTimeWithReadLock() throws Throwable {
      setupAnnotation(
          "test-lock",
          LockAcquisitionMode.SKIP_IMMEDIATELY,
          "5m",
          "",
          ThrowExceptionHandler.class,
          LockType.READ);
      when(readLock.tryLock(0, 300, TimeUnit.SECONDS)).thenReturn(true);
      when(readLock.isHeldByCurrentThread()).thenReturn(true);
      when(joinPoint.proceed()).thenReturn("result");

      aspect.handleDistributedLock(joinPoint);

      verify(readLock).tryLock(0, 300, TimeUnit.SECONDS);
    }

    @Test
    @DisplayName("Should use WAIT_AND_SKIP mode with write lock")
    void shouldUseWaitAndSkipModeWithWriteLock() throws Throwable {
      setupAnnotation(
          "test-lock",
          LockAcquisitionMode.WAIT_AND_SKIP,
          "",
          "",
          ThrowExceptionHandler.class,
          LockType.WRITE);
      when(writeLock.tryLock(60, 600, TimeUnit.SECONDS)).thenReturn(true);
      when(writeLock.isHeldByCurrentThread()).thenReturn(true);
      when(joinPoint.proceed()).thenReturn("result");

      aspect.handleDistributedLock(joinPoint);

      verify(writeLock).tryLock(60, 600, TimeUnit.SECONDS);
    }

    @Test
    @DisplayName("Should handle exception in method and release read lock")
    void shouldHandleExceptionAndReleaseReadLock() throws Throwable {
      setupAnnotation(
          "test-lock",
          LockAcquisitionMode.SKIP_IMMEDIATELY,
          "",
          "",
          ThrowExceptionHandler.class,
          LockType.READ);
      when(readLock.tryLock(0, 600, TimeUnit.SECONDS)).thenReturn(true);
      when(readLock.isHeldByCurrentThread()).thenReturn(true);
      when(joinPoint.proceed()).thenThrow(new RuntimeException("Test exception"));

      assertThrows(RuntimeException.class, () -> aspect.handleDistributedLock(joinPoint));

      verify(readLock).unlock();
    }
  }

  @Nested
  @DisplayName("Lease Expiration Detection Tests")
  class LeaseExpirationTests {

    @Test
    @DisplayName("Should not trigger lease expiration when execution is within lease time")
    void shouldNotTriggerLeaseExpirationWhenWithinLeaseTime() throws Throwable {
      setupAnnotation(
          "test-lock",
          LockAcquisitionMode.SKIP_IMMEDIATELY,
          "10m",
          "",
          ThrowExceptionHandler.class,
          LockType.REENTRANT,
          LeaseExpirationBehavior.THROW_EXCEPTION);
      when(redissonClient.getLock("lock:test-lock")).thenReturn(lock);
      when(lock.tryLock(0, 600, TimeUnit.SECONDS)).thenReturn(true);
      when(lock.isHeldByCurrentThread()).thenReturn(true);
      when(joinPoint.proceed()).thenReturn("result");

      Object result = aspect.handleDistributedLock(joinPoint);

      assertEquals("result", result);
    }

    @Test
    @DisplayName(
        "Should throw LeaseExpiredException when execution exceeds lease time with THROW_EXCEPTION")
    void shouldThrowLeaseExpiredExceptionWhenExecutionExceedsLeaseTime() throws Throwable {
      setupAnnotation(
          "test-lock",
          LockAcquisitionMode.SKIP_IMMEDIATELY,
          "1ms",
          "",
          ThrowExceptionHandler.class,
          LockType.REENTRANT,
          LeaseExpirationBehavior.THROW_EXCEPTION);
      when(redissonClient.getLock("lock:test-lock")).thenReturn(lock);
      when(lock.tryLock(0, 0, TimeUnit.SECONDS)).thenReturn(true);
      when(lock.isHeldByCurrentThread()).thenReturn(true);
      when(joinPoint.proceed())
          .thenAnswer(
              invocation -> {
                Thread.sleep(10);
                return "result";
              });

      LeaseExpiredException exception =
          assertThrows(LeaseExpiredException.class, () -> aspect.handleDistributedLock(joinPoint));

      assertEquals("lock:test-lock", exception.getLockKey());
      assertEquals("TestClass.testMethod", exception.getMethodName());
      assertEquals(1, exception.getLeaseTimeMs());
      assertTrue(exception.getExecutionTimeMs() >= 1);
    }

    @Test
    @DisplayName("Should ignore lease expiration with IGNORE behavior")
    void shouldIgnoreLeaseExpirationWithIgnoreBehavior() throws Throwable {
      setupAnnotation(
          "test-lock",
          LockAcquisitionMode.SKIP_IMMEDIATELY,
          "1ms",
          "",
          ThrowExceptionHandler.class,
          LockType.REENTRANT,
          LeaseExpirationBehavior.IGNORE);
      when(redissonClient.getLock("lock:test-lock")).thenReturn(lock);
      when(lock.tryLock(0, 0, TimeUnit.SECONDS)).thenReturn(true);
      when(lock.isHeldByCurrentThread()).thenReturn(true);
      when(joinPoint.proceed())
          .thenAnswer(
              invocation -> {
                Thread.sleep(10);
                return "result";
              });

      Object result = aspect.handleDistributedLock(joinPoint);

      assertEquals("result", result);
    }

    @Test
    @DisplayName("Should log warning when execution exceeds lease time with LOG_WARNING")
    void shouldLogWarningWhenExecutionExceedsLeaseTime() throws Throwable {
      setupAnnotation(
          "test-lock",
          LockAcquisitionMode.SKIP_IMMEDIATELY,
          "1ms",
          "",
          ThrowExceptionHandler.class,
          LockType.REENTRANT,
          LeaseExpirationBehavior.LOG_WARNING);
      when(redissonClient.getLock("lock:test-lock")).thenReturn(lock);
      when(lock.tryLock(0, 0, TimeUnit.SECONDS)).thenReturn(true);
      when(lock.isHeldByCurrentThread()).thenReturn(true);
      when(joinPoint.proceed())
          .thenAnswer(
              invocation -> {
                Thread.sleep(10);
                return "result";
              });

      Object result = aspect.handleDistributedLock(joinPoint);

      assertEquals("result", result);
      // Log warning is called but we can't easily verify it without a log appender
    }

    @Test
    @DisplayName("Should still release lock after lease expiration exception")
    void shouldStillReleaseLockAfterLeaseExpirationException() throws Throwable {
      setupAnnotation(
          "test-lock",
          LockAcquisitionMode.SKIP_IMMEDIATELY,
          "1ms",
          "",
          ThrowExceptionHandler.class,
          LockType.REENTRANT,
          LeaseExpirationBehavior.THROW_EXCEPTION);
      when(redissonClient.getLock("lock:test-lock")).thenReturn(lock);
      when(lock.tryLock(0, 0, TimeUnit.SECONDS)).thenReturn(true);
      when(lock.isHeldByCurrentThread()).thenReturn(true);
      when(joinPoint.proceed())
          .thenAnswer(
              invocation -> {
                Thread.sleep(10);
                return "result";
              });

      assertThrows(LeaseExpiredException.class, () -> aspect.handleDistributedLock(joinPoint));

      verify(lock).unlock();
    }

    @Test
    @DisplayName("LeaseExpiredException should contain correct information")
    void leaseExpiredExceptionShouldContainCorrectInformation() {
      LeaseExpiredException exception =
          new LeaseExpiredException("test-key", "TestClass.testMethod", 1000, 2000);

      assertEquals("test-key", exception.getLockKey());
      assertEquals("TestClass.testMethod", exception.getMethodName());
      assertEquals(1000, exception.getLeaseTimeMs());
      assertEquals(2000, exception.getExecutionTimeMs());
      assertTrue(exception.getMessage().contains("test-key"));
      assertTrue(exception.getMessage().contains("TestClass.testMethod"));
      assertTrue(exception.getMessage().contains("1000"));
      assertTrue(exception.getMessage().contains("2000"));
    }
  }

  @Nested
  @DisplayName("Custom Skip Handler Tests")
  class CustomSkipHandlerTests {

    @Test
    @DisplayName("Should use ThrowExceptionHandler when specified")
    void shouldUseThrowExceptionHandler() throws Throwable {
      setupAnnotation(
          "test-lock", LockAcquisitionMode.SKIP_IMMEDIATELY, "", "", ThrowExceptionHandler.class);
      when(redissonClient.getLock("lock:test-lock")).thenReturn(lock);
      when(lock.tryLock(0, 600, TimeUnit.SECONDS)).thenReturn(false);

      assertThrows(LockNotAcquiredException.class, () -> aspect.handleDistributedLock(joinPoint));
    }

    @Test
    @DisplayName("Should use ReturnDefaultHandler when specified")
    void shouldUseReturnDefaultHandler() throws Throwable {
      setupAnnotation(
          "test-lock", LockAcquisitionMode.SKIP_IMMEDIATELY, "", "", ReturnDefaultHandler.class);
      when(redissonClient.getLock("lock:test-lock")).thenReturn(lock);
      when(lock.tryLock(0, 600, TimeUnit.SECONDS)).thenReturn(false);

      Object result = aspect.handleDistributedLock(joinPoint);

      assertNull(result);
    }

    @Test
    @DisplayName("Should use custom handler that returns specific value")
    void shouldUseCustomHandlerReturningSpecificValue() throws Throwable {
      setupAnnotation(
          "test-lock", LockAcquisitionMode.SKIP_IMMEDIATELY, "", "", FallbackValueHandler.class);
      when(redissonClient.getLock("lock:test-lock")).thenReturn(lock);
      when(lock.tryLock(0, 600, TimeUnit.SECONDS)).thenReturn(false);

      Object result = aspect.handleDistributedLock(joinPoint);

      assertEquals("fallback-value", result);
    }

    @Test
    @DisplayName("Should pass correct context to custom handler")
    void shouldPassCorrectContextToCustomHandler() throws Throwable {
      setupAnnotation(
          "test-lock", LockAcquisitionMode.SKIP_IMMEDIATELY, "", "", ContextCapturingHandler.class);
      when(redissonClient.getLock("lock:test-lock")).thenReturn(lock);
      when(lock.tryLock(0, 600, TimeUnit.SECONDS)).thenReturn(false);
      when(joinPoint.getArgs()).thenReturn(new Object[] {"arg1", 42});

      aspect.handleDistributedLock(joinPoint);

      assertNotNull(ContextCapturingHandler.capturedContext);
      assertEquals("lock:test-lock", ContextCapturingHandler.capturedContext.lockKey());
      assertEquals("TestClass.testMethod", ContextCapturingHandler.capturedContext.methodName());
      assertArrayEquals(new Object[] {"arg1", 42}, ContextCapturingHandler.capturedContext.args());
    }

    @Test
    @DisplayName("ReturnDefaultHandler should return false for boolean return type")
    void returnDefaultHandlerShouldReturnFalseForBoolean() throws Throwable {
      when(methodSignature.getReturnType()).thenReturn(boolean.class);
      setupAnnotation(
          "test-lock", LockAcquisitionMode.SKIP_IMMEDIATELY, "", "", ReturnDefaultHandler.class);
      when(redissonClient.getLock("lock:test-lock")).thenReturn(lock);
      when(lock.tryLock(0, 600, TimeUnit.SECONDS)).thenReturn(false);

      Object result = aspect.handleDistributedLock(joinPoint);

      assertEquals(false, result);
    }

    @Test
    @DisplayName("ReturnDefaultHandler should return 0 for int return type")
    void returnDefaultHandlerShouldReturnZeroForInt() throws Throwable {
      when(methodSignature.getReturnType()).thenReturn(int.class);
      setupAnnotation(
          "test-lock", LockAcquisitionMode.SKIP_IMMEDIATELY, "", "", ReturnDefaultHandler.class);
      when(redissonClient.getLock("lock:test-lock")).thenReturn(lock);
      when(lock.tryLock(0, 600, TimeUnit.SECONDS)).thenReturn(false);

      Object result = aspect.handleDistributedLock(joinPoint);

      assertEquals(0, result);
    }

    @Test
    @DisplayName("Should throw IllegalStateException when handler has no public no-arg constructor")
    void shouldThrowIllegalStateExceptionWhenHandlerCannotBeInstantiated() throws Throwable {
      setupAnnotation(
          "test-lock",
          LockAcquisitionMode.SKIP_IMMEDIATELY,
          "",
          "",
          PrivateConstructorHandler.class);
      when(redissonClient.getLock("lock:test-lock")).thenReturn(lock);
      when(lock.tryLock(0, 600, TimeUnit.SECONDS)).thenReturn(false);

      IllegalStateException exception =
          assertThrows(IllegalStateException.class, () -> aspect.handleDistributedLock(joinPoint));

      assertTrue(exception.getMessage().contains("Failed to instantiate skip handler"));
      assertTrue(exception.getMessage().contains("PrivateConstructorHandler"));
      assertTrue(exception.getMessage().contains("public no-argument constructor"));
      assertInstanceOf(ReflectiveOperationException.class, exception.getCause());
    }
  }

  /** Test handler that returns a specific fallback value. */
  public static class FallbackValueHandler implements LockSkipHandler {
    @Override
    public Object handle(LockContext context) {
      return "fallback-value";
    }
  }

  /** Test handler that captures the context for verification. */
  public static class ContextCapturingHandler implements LockSkipHandler {
    public static LockContext capturedContext;

    @Override
    public Object handle(LockContext context) {
      capturedContext = context;
      return null;
    }
  }

  /** Test handler with private constructor to verify error handling. */
  public static class PrivateConstructorHandler implements LockSkipHandler {
    private PrivateConstructorHandler() {
      // Private constructor - cannot be instantiated via reflection
    }

    @Override
    public Object handle(LockContext context) {
      return null;
    }
  }
}
