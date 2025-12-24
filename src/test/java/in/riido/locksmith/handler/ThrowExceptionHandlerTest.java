package in.riido.locksmith.handler;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import in.riido.locksmith.exception.LockNotAcquiredException;
import java.lang.reflect.Method;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("ThrowExceptionHandler Tests")
class ThrowExceptionHandlerTest {

  private ThrowExceptionHandler handler;
  private Method mockMethod;

  @BeforeEach
  void setUp() {
    handler = new ThrowExceptionHandler();
    mockMethod = mock(Method.class);
  }

  private LockContext createContext(String lockKey, String methodName) {
    return new LockContext(lockKey, methodName, mockMethod, new Object[] {}, void.class);
  }

  @Nested
  @DisplayName("Exception Throwing Tests")
  class ExceptionThrowingTests {

    @Test
    @DisplayName("Should throw LockNotAcquiredException")
    void shouldThrowLockNotAcquiredException() {
      LockContext context = createContext("test-lock", "TestClass.testMethod");

      assertThrows(LockNotAcquiredException.class, () -> handler.handle(context));
    }

    @Test
    @DisplayName("Should include lock key in exception")
    void shouldIncludeLockKeyInException() {
      LockContext context = createContext("my-lock-key", "TestClass.testMethod");

      LockNotAcquiredException exception =
          assertThrows(LockNotAcquiredException.class, () -> handler.handle(context));

      assertEquals("my-lock-key", exception.getLockKey());
    }

    @Test
    @DisplayName("Should include method name in exception")
    void shouldIncludeMethodNameInException() {
      LockContext context = createContext("test-lock", "MyService.processOrder");

      LockNotAcquiredException exception =
          assertThrows(LockNotAcquiredException.class, () -> handler.handle(context));

      assertEquals("MyService.processOrder", exception.getMethodName());
    }

    @Test
    @DisplayName("Should include lock key in exception message")
    void shouldIncludeLockKeyInExceptionMessage() {
      LockContext context = createContext("unique-lock-key", "TestClass.testMethod");

      LockNotAcquiredException exception =
          assertThrows(LockNotAcquiredException.class, () -> handler.handle(context));

      assertTrue(exception.getMessage().contains("unique-lock-key"));
    }

    @Test
    @DisplayName("Should include method name in exception message")
    void shouldIncludeMethodNameInExceptionMessage() {
      LockContext context = createContext("test-lock", "OrderService.createOrder");

      LockNotAcquiredException exception =
          assertThrows(LockNotAcquiredException.class, () -> handler.handle(context));

      assertTrue(exception.getMessage().contains("OrderService.createOrder"));
    }
  }

  @Nested
  @DisplayName("Context Handling Tests")
  class ContextHandlingTests {

    @Test
    @DisplayName("Should handle context with special characters in lock key")
    void shouldHandleContextWithSpecialCharactersInLockKey() {
      LockContext context = createContext("lock:user:123:order", "TestClass.testMethod");

      LockNotAcquiredException exception =
          assertThrows(LockNotAcquiredException.class, () -> handler.handle(context));

      assertEquals("lock:user:123:order", exception.getLockKey());
    }

    @Test
    @DisplayName("Should handle context with empty args array")
    void shouldHandleContextWithEmptyArgsArray() {
      LockContext context =
          new LockContext(
              "test-lock", "TestClass.testMethod", mockMethod, new Object[] {}, void.class);

      assertThrows(LockNotAcquiredException.class, () -> handler.handle(context));
    }

    @Test
    @DisplayName("Should handle context with args array")
    void shouldHandleContextWithArgsArray() {
      LockContext context =
          new LockContext(
              "test-lock",
              "TestClass.testMethod",
              mockMethod,
              new Object[] {"arg1", 42},
              void.class);

      LockNotAcquiredException exception =
          assertThrows(LockNotAcquiredException.class, () -> handler.handle(context));

      assertEquals("test-lock", exception.getLockKey());
    }

    @Test
    @DisplayName("Should handle various return types in context")
    void shouldHandleVariousReturnTypesInContext() {
      LockContext context =
          new LockContext(
              "test-lock", "TestClass.testMethod", mockMethod, new Object[] {}, String.class);

      assertThrows(LockNotAcquiredException.class, () -> handler.handle(context));
    }
  }

  @Nested
  @DisplayName("Handler Instantiation Tests")
  class HandlerInstantiationTests {

    @Test
    @DisplayName("Should be instantiable with no-arg constructor")
    void shouldBeInstantiableWithNoArgConstructor() {
      ThrowExceptionHandler handler = new ThrowExceptionHandler();

      assertNotNull(handler);
    }

    @Test
    @DisplayName("Should implement LockSkipHandler interface")
    void shouldImplementLockSkipHandlerInterface() {
      assertTrue(LockSkipHandler.class.isAssignableFrom(ThrowExceptionHandler.class));
    }

    @Test
    @DisplayName("Should be instantiable via reflection")
    void shouldBeInstantiableViaReflection() throws Exception {
      ThrowExceptionHandler handler =
          ThrowExceptionHandler.class.getDeclaredConstructor().newInstance();

      assertNotNull(handler);
    }
  }

  @Nested
  @DisplayName("Edge Case Tests")
  class EdgeCaseTests {

    @Test
    @DisplayName("Should handle long lock key")
    void shouldHandleLongLockKey() {
      String longLockKey = "a".repeat(1000);
      LockContext context = createContext(longLockKey, "TestClass.testMethod");

      LockNotAcquiredException exception =
          assertThrows(LockNotAcquiredException.class, () -> handler.handle(context));

      assertEquals(longLockKey, exception.getLockKey());
    }

    @Test
    @DisplayName("Should handle long method name")
    void shouldHandleLongMethodName() {
      String longMethodName = "VeryLongClassName.veryLongMethodName" + "a".repeat(100);
      LockContext context = createContext("test-lock", longMethodName);

      LockNotAcquiredException exception =
          assertThrows(LockNotAcquiredException.class, () -> handler.handle(context));

      assertEquals(longMethodName, exception.getMethodName());
    }

    @Test
    @DisplayName("Should handle unicode characters in lock key")
    void shouldHandleUnicodeCharactersInLockKey() {
      LockContext context = createContext("lock:用户:订单", "TestClass.testMethod");

      LockNotAcquiredException exception =
          assertThrows(LockNotAcquiredException.class, () -> handler.handle(context));

      assertEquals("lock:用户:订单", exception.getLockKey());
    }
  }
}
