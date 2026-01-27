package in.riido.locksmith.exception;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("RateLimitExceededException Tests")
class RateLimitExceededExceptionTest {

  @Nested
  @DisplayName("Constructor Tests")
  class ConstructorTests {

    @Test
    @DisplayName("Should create exception with rate limit key and method name")
    void shouldCreateExceptionWithKeyAndMethodName() {
      RateLimitExceededException exception =
          new RateLimitExceededException("api-key", "processRequest");

      assertEquals("api-key", exception.getRateLimitKey());
      assertEquals("processRequest", exception.getMethodName());
      assertTrue(exception.getMessage().contains("api-key"));
      assertTrue(exception.getMessage().contains("processRequest"));
    }

    @Test
    @DisplayName("Should create exception with empty values")
    void shouldCreateExceptionWithEmptyValues() {
      RateLimitExceededException exception = new RateLimitExceededException("", "");

      assertEquals("", exception.getRateLimitKey());
      assertEquals("", exception.getMethodName());
    }
  }

  @Nested
  @DisplayName("Getter Tests")
  class GetterTests {

    @Test
    @DisplayName("Should return correct rate limit key")
    void shouldReturnCorrectRateLimitKey() {
      RateLimitExceededException exception =
          new RateLimitExceededException("user:123:api", "handleRequest");

      assertEquals("user:123:api", exception.getRateLimitKey());
    }

    @Test
    @DisplayName("Should return correct method name")
    void shouldReturnCorrectMethodName() {
      RateLimitExceededException exception =
          new RateLimitExceededException("rate-key", "myServiceMethod");

      assertEquals("myServiceMethod", exception.getMethodName());
    }
  }

  @Nested
  @DisplayName("Exception Hierarchy Tests")
  class ExceptionHierarchyTests {

    @Test
    @DisplayName("Should be a RuntimeException")
    void shouldBeRuntimeException() {
      RateLimitExceededException exception = new RateLimitExceededException("key", "method");

      assertTrue(exception instanceof RuntimeException);
    }

    @Test
    @DisplayName("Should be catchable as RuntimeException")
    void shouldBeCatchableAsRuntimeException() {
      try {
        throw new RateLimitExceededException("test-key", "testMethod");
      } catch (RuntimeException e) {
        assertTrue(e instanceof RateLimitExceededException);
      }
    }

    @Test
    @DisplayName("Should be throwable and catchable")
    void shouldBeThrowableAndCatchable() {
      assertThrows(
          RateLimitExceededException.class,
          () -> {
            throw new RateLimitExceededException("key", "method");
          });
    }
  }

  @Nested
  @DisplayName("Message Format Tests")
  class MessageFormatTests {

    @Test
    @DisplayName("Should format message with rate limit key and method name")
    void shouldFormatMessageCorrectly() {
      RateLimitExceededException exception =
          new RateLimitExceededException("api:limit", "callExternalApi");

      String message = exception.getMessage();
      assertNotNull(message);
      assertTrue(message.contains("api:limit"));
      assertTrue(message.contains("callExternalApi"));
    }

    @Test
    @DisplayName("Should indicate rate limit exceeded in message")
    void shouldIndicateRateLimitExceeded() {
      RateLimitExceededException exception = new RateLimitExceededException("key", "method");

      String message = exception.getMessage().toLowerCase();
      assertTrue(
          message.contains("rate") || message.contains("limit") || message.contains("exceeded"),
          "Message should indicate rate limit was exceeded");
    }
  }

  @Nested
  @DisplayName("Edge Case Tests")
  class EdgeCaseTests {

    @Test
    @DisplayName("Should handle special characters in key")
    void shouldHandleSpecialCharactersInKey() {
      RateLimitExceededException exception =
          new RateLimitExceededException("user:123#api@test", "method");

      assertEquals("user:123#api@test", exception.getRateLimitKey());
    }

    @Test
    @DisplayName("Should handle very long key")
    void shouldHandleVeryLongKey() {
      String longKey = "a".repeat(1000);
      RateLimitExceededException exception = new RateLimitExceededException(longKey, "method");

      assertEquals(longKey, exception.getRateLimitKey());
    }

    @Test
    @DisplayName("Should handle unicode characters")
    void shouldHandleUnicodeCharacters() {
      RateLimitExceededException exception = new RateLimitExceededException("用户:123", "测试方法");

      assertEquals("用户:123", exception.getRateLimitKey());
      assertEquals("测试方法", exception.getMethodName());
    }
  }
}
