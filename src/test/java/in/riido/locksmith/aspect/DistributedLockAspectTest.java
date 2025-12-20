package in.riido.locksmith.aspect;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import in.riido.locksmith.DistributedLock;
import in.riido.locksmith.LockAcquisitionMode;
import in.riido.locksmith.SkipBehavior;
import in.riido.locksmith.autoconfigure.LocksmithProperties;
import in.riido.locksmith.exception.LockNotAcquiredException;
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
      SkipBehavior onSkip) {
    DistributedLock annotation = mock(DistributedLock.class);
    when(annotation.key()).thenReturn(key);
    when(annotation.mode()).thenReturn(mode);
    when(annotation.leaseTime()).thenReturn(leaseTime);
    when(annotation.waitTime()).thenReturn(waitTime);
    when(annotation.onSkip()).thenReturn(onSkip);

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

    public record TestUser(String id) {
    }
  }

  @Nested
  @DisplayName("Lock Acquisition Tests - SKIP_IMMEDIATELY Mode")
  class SkipImmediatelyModeTests {

    @Test
    @DisplayName("Should acquire lock and execute method when lock is available")
    void shouldAcquireLockAndExecuteMethod() throws Throwable {
      setupAnnotation(
          "test-lock", LockAcquisitionMode.SKIP_IMMEDIATELY, "", "", SkipBehavior.THROW_EXCEPTION);
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
          "test-lock", LockAcquisitionMode.SKIP_IMMEDIATELY, "", "", SkipBehavior.RETURN_DEFAULT);
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
          "test-lock", LockAcquisitionMode.SKIP_IMMEDIATELY, "", "", SkipBehavior.THROW_EXCEPTION);
      when(redissonClient.getLock("lock:test-lock")).thenReturn(lock);
      when(lock.tryLock(eq(0L), anyLong(), eq(TimeUnit.SECONDS))).thenReturn(true);
      when(lock.isHeldByCurrentThread()).thenReturn(true);

      aspect.handleDistributedLock(joinPoint);

      verify(lock).tryLock(eq(0L), anyLong(), eq(TimeUnit.SECONDS));
    }
  }

  @Nested
  @DisplayName("Lock Acquisition Tests - WAIT_AND_SKIP Mode")
  class WaitAndSkipModeTests {

    @Test
    @DisplayName("Should wait for lock and execute method when lock becomes available")
    void shouldWaitForLockAndExecuteMethod() throws Throwable {
      setupAnnotation(
          "test-lock", LockAcquisitionMode.WAIT_AND_SKIP, "", "", SkipBehavior.THROW_EXCEPTION);
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
          "test-lock", LockAcquisitionMode.WAIT_AND_SKIP, "", "", SkipBehavior.RETURN_DEFAULT);
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
          "test-lock", LockAcquisitionMode.WAIT_AND_SKIP, "", "", SkipBehavior.THROW_EXCEPTION);
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
          "test-lock", LockAcquisitionMode.SKIP_IMMEDIATELY, "", "", SkipBehavior.THROW_EXCEPTION);
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
          "test-lock", LockAcquisitionMode.SKIP_IMMEDIATELY, "", "", SkipBehavior.THROW_EXCEPTION);
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
          "test-lock", LockAcquisitionMode.SKIP_IMMEDIATELY, "", "", SkipBehavior.THROW_EXCEPTION);
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
          "test-lock", LockAcquisitionMode.SKIP_IMMEDIATELY, "", "", SkipBehavior.THROW_EXCEPTION);
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
          "test-lock", LockAcquisitionMode.SKIP_IMMEDIATELY, "5m", "", SkipBehavior.THROW_EXCEPTION);
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
          "test-lock", LockAcquisitionMode.WAIT_AND_SKIP, "", "30s", SkipBehavior.THROW_EXCEPTION);
      when(redissonClient.getLock("lock:test-lock")).thenReturn(lock);
      when(lock.tryLock(30, 600, TimeUnit.SECONDS)).thenReturn(true);
      when(lock.isHeldByCurrentThread()).thenReturn(true);

      aspect.handleDistributedLock(joinPoint);

      verify(lock).tryLock(30, 600, TimeUnit.SECONDS);
    }
  }

  @Nested
  @DisplayName("Lock Key Tests")
  class LockKeyTests {

    @Test
    @DisplayName("Should use key prefix from properties")
    void shouldUseKeyPrefixFromProperties() throws Throwable {
      setupAnnotation(
          "my-task", LockAcquisitionMode.SKIP_IMMEDIATELY, "", "", SkipBehavior.RETURN_DEFAULT);
      when(redissonClient.getLock("lock:my-task")).thenReturn(lock);
      when(lock.tryLock(anyLong(), anyLong(), eq(TimeUnit.SECONDS))).thenReturn(false);

      aspect.handleDistributedLock(joinPoint);

      verify(redissonClient).getLock("lock:my-task");
    }

    @Test
    @DisplayName("Should throw IllegalArgumentException when key is empty")
    void shouldThrowIllegalArgumentExceptionWhenKeyIsEmpty() {
      setupAnnotation(
          "", LockAcquisitionMode.SKIP_IMMEDIATELY, "", "", SkipBehavior.THROW_EXCEPTION);

      IllegalArgumentException thrown =
          assertThrows(
              IllegalArgumentException.class, () -> aspect.handleDistributedLock(joinPoint));

      assertTrue(thrown.getMessage().contains("must not be blank"));
    }

    @Test
    @DisplayName("Should throw IllegalArgumentException when key is blank")
    void shouldThrowIllegalArgumentExceptionWhenKeyIsBlank() {
      setupAnnotation(
          "   ", LockAcquisitionMode.SKIP_IMMEDIATELY, "", "", SkipBehavior.THROW_EXCEPTION);

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
          "test-lock", LockAcquisitionMode.SKIP_IMMEDIATELY, "", "", SkipBehavior.THROW_EXCEPTION);
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
          "test-lock", LockAcquisitionMode.SKIP_IMMEDIATELY, "", "", SkipBehavior.RETURN_DEFAULT);
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
          "test-lock", LockAcquisitionMode.SKIP_IMMEDIATELY, "", "", SkipBehavior.RETURN_DEFAULT);
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
          "test-lock", LockAcquisitionMode.SKIP_IMMEDIATELY, "", "", SkipBehavior.RETURN_DEFAULT);
      when(redissonClient.getLock("lock:test-lock")).thenReturn(lock);
      when(lock.tryLock(0, 600, TimeUnit.SECONDS)).thenReturn(false);

      Object result = aspect.handleDistributedLock(joinPoint);

      assertNull(result);
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
        LocksmithProperties props =
            new LocksmithProperties(null, Duration.ofSeconds(60), "lock:");

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
        LocksmithProperties props =
            new LocksmithProperties(Duration.ofMinutes(10), null, "lock:");

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
            new LocksmithProperties(Duration.ofMinutes(10), Duration.ofSeconds(60), "distributed-lock");

        assertEquals("distributed-lock", props.keyPrefix());
      }

      @Test
      @DisplayName("Should preserve key prefix with special characters")
      void shouldPreserveKeyPrefixWithSpecialCharacters() {
        LocksmithProperties props =
            new LocksmithProperties(Duration.ofMinutes(10), Duration.ofSeconds(60), "app:env:lock:");

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
  }
}
