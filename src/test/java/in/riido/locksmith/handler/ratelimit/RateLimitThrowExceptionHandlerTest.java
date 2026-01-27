package in.riido.locksmith.handler.ratelimit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import in.riido.locksmith.exception.RateLimitExceededException;
import in.riido.locksmith.models.RateLimitContext;
import java.lang.reflect.Method;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("RateLimitThrowExceptionHandler Tests")
class RateLimitThrowExceptionHandlerTest {

  private RateLimitThrowExceptionHandler handler;

  @BeforeEach
  void setUp() {
    handler = new RateLimitThrowExceptionHandler();
  }

  @Nested
  @DisplayName("Handle Method Tests")
  class HandleMethodTests {

    @Test
    @DisplayName("Should throw RateLimitExceededException")
    void shouldThrowRateLimitExceededException() throws NoSuchMethodException {
      Method method = TestService.class.getMethod("testMethod");
      RateLimitContext context =
          new RateLimitContext("test-key", "testMethod", method, new Object[] {}, String.class);

      assertThrows(RateLimitExceededException.class, () -> handler.handle(context));
    }

    @Test
    @DisplayName("Should include rate limit key in exception")
    void shouldIncludeRateLimitKeyInException() throws NoSuchMethodException {
      Method method = TestService.class.getMethod("testMethod");
      RateLimitContext context =
          new RateLimitContext("api:user:123", "testMethod", method, new Object[] {}, String.class);

      RateLimitExceededException exception =
          assertThrows(RateLimitExceededException.class, () -> handler.handle(context));

      assertEquals("api:user:123", exception.getRateLimitKey());
    }

    @Test
    @DisplayName("Should include method name in exception")
    void shouldIncludeMethodNameInException() throws NoSuchMethodException {
      Method method = TestService.class.getMethod("testMethod");
      RateLimitContext context =
          new RateLimitContext("test-key", "myCustomMethod", method, new Object[] {}, String.class);

      RateLimitExceededException exception =
          assertThrows(RateLimitExceededException.class, () -> handler.handle(context));

      assertEquals("myCustomMethod", exception.getMethodName());
    }
  }

  @Nested
  @DisplayName("Context Handling Tests")
  class ContextHandlingTests {

    @Test
    @DisplayName("Should handle context with special characters in key")
    void shouldHandleContextWithSpecialCharactersInKey() throws NoSuchMethodException {
      Method method = TestService.class.getMethod("testMethod");
      RateLimitContext context =
          new RateLimitContext(
              "special:key#123", "testMethod", method, new Object[] {}, String.class);

      RateLimitExceededException exception =
          assertThrows(RateLimitExceededException.class, () -> handler.handle(context));

      assertEquals("special:key#123", exception.getRateLimitKey());
    }

    @Test
    @DisplayName("Should handle context with empty key")
    void shouldHandleContextWithEmptyKey() throws NoSuchMethodException {
      Method method = TestService.class.getMethod("testMethod");
      RateLimitContext context =
          new RateLimitContext("", "testMethod", method, new Object[] {}, String.class);

      RateLimitExceededException exception =
          assertThrows(RateLimitExceededException.class, () -> handler.handle(context));

      assertEquals("", exception.getRateLimitKey());
    }
  }

  // Test service for reflection
  public static class TestService {
    public String testMethod() {
      return "test";
    }
  }
}
