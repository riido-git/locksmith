package in.riido.locksmith.exception;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("LockNotAcquiredException Tests")
class LockNotAcquiredExceptionTest {

  @Nested
  @DisplayName("Constructor Tests")
  class ConstructorTests {

    @Test
    @DisplayName("Should create exception with correct lock key")
    void shouldCreateExceptionWithCorrectLockKey() {
      LockNotAcquiredException exception =
          new LockNotAcquiredException("lock:my-task", "MyService.doWork");

      assertEquals("lock:my-task", exception.getLockKey());
    }

    @Test
    @DisplayName("Should create exception with correct method name")
    void shouldCreateExceptionWithCorrectMethodName() {
      LockNotAcquiredException exception =
          new LockNotAcquiredException("lock:my-task", "MyService.doWork");

      assertEquals("MyService.doWork", exception.getMethodName());
    }

    @Test
    @DisplayName("Should create exception with formatted message containing lock key")
    void shouldCreateExceptionWithMessageContainingLockKey() {
      LockNotAcquiredException exception =
          new LockNotAcquiredException("lock:my-task", "MyService.doWork");

      assertTrue(exception.getMessage().contains("lock:my-task"));
    }

    @Test
    @DisplayName("Should create exception with formatted message containing method name")
    void shouldCreateExceptionWithMessageContainingMethodName() {
      LockNotAcquiredException exception =
          new LockNotAcquiredException("lock:my-task", "MyService.doWork");

      assertTrue(exception.getMessage().contains("MyService.doWork"));
    }

    @Test
    @DisplayName("Should create exception with informative message about lock failure")
    void shouldCreateExceptionWithInformativeMessage() {
      LockNotAcquiredException exception =
          new LockNotAcquiredException("lock:my-task", "MyService.doWork");

      assertTrue(exception.getMessage().contains("Failed to acquire distributed lock"));
      assertTrue(exception.getMessage().contains("Another instance is currently executing"));
    }
  }

  @Nested
  @DisplayName("Getter Tests")
  class GetterTests {

    @Test
    @DisplayName("getLockKey should return the lock key")
    void getLockKeyShouldReturnLockKey() {
      LockNotAcquiredException exception =
          new LockNotAcquiredException("prefix:task-123", "TaskService.process");

      assertEquals("prefix:task-123", exception.getLockKey());
    }

    @Test
    @DisplayName("getMethodName should return the method name")
    void getMethodNameShouldReturnMethodName() {
      LockNotAcquiredException exception =
          new LockNotAcquiredException("prefix:task-123", "TaskService.process");

      assertEquals("TaskService.process", exception.getMethodName());
    }
  }

  @Nested
  @DisplayName("Exception Hierarchy Tests")
  class ExceptionHierarchyTests {

    @Test
    @DisplayName("Should be a RuntimeException")
    void shouldBeRuntimeException() {
      LockNotAcquiredException exception = new LockNotAcquiredException("lock:test", "Test.method");

      assertInstanceOf(RuntimeException.class, exception);
    }

    @Test
    @DisplayName("Should be catchable as Exception")
    void shouldBeCatchableAsException() {
      LockNotAcquiredException exception = new LockNotAcquiredException("lock:test", "Test.method");

      assertInstanceOf(Exception.class, exception);
    }

    @Test
    @DisplayName("Should be catchable as Throwable")
    void shouldBeCatchableAsThrowable() {
      LockNotAcquiredException exception = new LockNotAcquiredException("lock:test", "Test.method");

      assertInstanceOf(Throwable.class, exception);
    }
  }

  @Nested
  @DisplayName("Edge Case Tests")
  class EdgeCaseTests {

    @Test
    @DisplayName("Should handle empty lock key")
    void shouldHandleEmptyLockKey() {
      LockNotAcquiredException exception = new LockNotAcquiredException("", "MyService.doWork");

      assertEquals("", exception.getLockKey());
      assertTrue(exception.getMessage().contains("[]"));
    }

    @Test
    @DisplayName("Should handle empty method name")
    void shouldHandleEmptyMethodName() {
      LockNotAcquiredException exception = new LockNotAcquiredException("lock:test", "");

      assertEquals("", exception.getMethodName());
      assertTrue(exception.getMessage().contains("[]"));
    }

    @Test
    @DisplayName("Should handle special characters in lock key")
    void shouldHandleSpecialCharactersInLockKey() {
      String specialKey = "lock:user:123:task:456";
      LockNotAcquiredException exception =
          new LockNotAcquiredException(specialKey, "MyService.doWork");

      assertEquals(specialKey, exception.getLockKey());
      assertTrue(exception.getMessage().contains(specialKey));
    }

    @Test
    @DisplayName("Should handle long lock key and method name")
    void shouldHandleLongLockKeyAndMethodName() {
      String longKey = "lock:" + "a".repeat(100);
      String longMethod = "com.example.very.long.package.name.ServiceClass.veryLongMethodName";
      LockNotAcquiredException exception = new LockNotAcquiredException(longKey, longMethod);

      assertEquals(longKey, exception.getLockKey());
      assertEquals(longMethod, exception.getMethodName());
    }
  }

  @Nested
  @DisplayName("Message Format Tests")
  class MessageFormatTests {

    @Test
    @DisplayName("Should format message with lock key in brackets")
    void shouldFormatMessageWithLockKeyInBrackets() {
      LockNotAcquiredException exception =
          new LockNotAcquiredException("lock:my-task", "MyService.doWork");

      assertTrue(exception.getMessage().contains("[lock:my-task]"));
    }

    @Test
    @DisplayName("Should format message with method name in brackets")
    void shouldFormatMessageWithMethodNameInBrackets() {
      LockNotAcquiredException exception =
          new LockNotAcquiredException("lock:my-task", "MyService.doWork");

      assertTrue(exception.getMessage().contains("[MyService.doWork]"));
    }

    @Test
    @DisplayName("Should have consistent message format")
    void shouldHaveConsistentMessageFormat() {
      LockNotAcquiredException exception =
          new LockNotAcquiredException("lock:task", "Service.method");

      String expectedPattern =
          "Failed to acquire distributed lock [lock:task] for method [Service.method]. "
              + "Another instance is currently executing this task.";
      assertEquals(expectedPattern, exception.getMessage());
    }
  }
}
