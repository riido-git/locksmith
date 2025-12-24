package in.riido.locksmith.handler;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.lang.reflect.Method;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("LockContext Tests")
class LockContextTest {

  @Nested
  @DisplayName("Record Creation Tests")
  class RecordCreationTests {

    @Test
    @DisplayName("Should create LockContext with all parameters")
    void shouldCreateLockContextWithAllParameters() {
      Method mockMethod = mock(Method.class);
      Object[] args = new Object[] {"arg1", 42};

      LockContext context =
          new LockContext("test-lock", "TestClass.testMethod", mockMethod, args, String.class);

      assertEquals("test-lock", context.lockKey());
      assertEquals("TestClass.testMethod", context.methodName());
      assertEquals(mockMethod, context.method());
      assertArrayEquals(args, context.args());
      assertEquals(String.class, context.returnType());
    }

    @Test
    @DisplayName("Should create LockContext with empty args array")
    void shouldCreateLockContextWithEmptyArgsArray() {
      Method mockMethod = mock(Method.class);

      LockContext context =
          new LockContext(
              "test-lock", "TestClass.testMethod", mockMethod, new Object[] {}, void.class);

      assertEquals(0, context.args().length);
    }
  }

  @Nested
  @DisplayName("Null Validation Tests")
  class NullValidationTests {

    @Test
    @DisplayName("Should throw NullPointerException when lockKey is null")
    void shouldThrowNpeWhenLockKeyIsNull() {
      Method mockMethod = mock(Method.class);

      NullPointerException exception =
          assertThrows(
              NullPointerException.class,
              () ->
                  new LockContext(
                      null, "TestClass.testMethod", mockMethod, new Object[] {}, void.class));

      assertEquals("lockKey must not be null", exception.getMessage());
    }

    @Test
    @DisplayName("Should throw NullPointerException when methodName is null")
    void shouldThrowNpeWhenMethodNameIsNull() {
      Method mockMethod = mock(Method.class);

      NullPointerException exception =
          assertThrows(
              NullPointerException.class,
              () -> new LockContext("test-lock", null, mockMethod, new Object[] {}, void.class));

      assertEquals("methodName must not be null", exception.getMessage());
    }

    @Test
    @DisplayName("Should throw NullPointerException when method is null")
    void shouldThrowNpeWhenMethodIsNull() {
      NullPointerException exception =
          assertThrows(
              NullPointerException.class,
              () ->
                  new LockContext(
                      "test-lock", "TestClass.testMethod", null, new Object[] {}, void.class));

      assertEquals("method must not be null", exception.getMessage());
    }

    @Test
    @DisplayName("Should throw NullPointerException when args is null")
    void shouldThrowNpeWhenArgsIsNull() {
      Method mockMethod = mock(Method.class);

      NullPointerException exception =
          assertThrows(
              NullPointerException.class,
              () ->
                  new LockContext(
                      "test-lock", "TestClass.testMethod", mockMethod, null, void.class));

      assertEquals("args must not be null", exception.getMessage());
    }

    @Test
    @DisplayName("Should throw NullPointerException when returnType is null")
    void shouldThrowNpeWhenReturnTypeIsNull() {
      Method mockMethod = mock(Method.class);

      NullPointerException exception =
          assertThrows(
              NullPointerException.class,
              () ->
                  new LockContext(
                      "test-lock", "TestClass.testMethod", mockMethod, new Object[] {}, null));

      assertEquals("returnType must not be null", exception.getMessage());
    }
  }

  @Nested
  @DisplayName("Record Equality Tests")
  class RecordEqualityTests {

    @Test
    @DisplayName("Should be equal when all fields are the same")
    void shouldBeEqualWhenAllFieldsAreSame() {
      Method mockMethod = mock(Method.class);
      Object[] args = new Object[] {"arg1"};

      LockContext context1 =
          new LockContext("test-lock", "TestClass.testMethod", mockMethod, args, String.class);
      LockContext context2 =
          new LockContext("test-lock", "TestClass.testMethod", mockMethod, args, String.class);

      assertEquals(context1, context2);
      assertEquals(context1.hashCode(), context2.hashCode());
    }

    @Test
    @DisplayName("Should not be equal when lockKey is different")
    void shouldNotBeEqualWhenLockKeyIsDifferent() {
      Method mockMethod = mock(Method.class);
      Object[] args = new Object[] {};

      LockContext context1 =
          new LockContext("lock-1", "TestClass.testMethod", mockMethod, args, void.class);
      LockContext context2 =
          new LockContext("lock-2", "TestClass.testMethod", mockMethod, args, void.class);

      assertNotEquals(context1, context2);
    }

    @Test
    @DisplayName("Should not be equal when methodName is different")
    void shouldNotBeEqualWhenMethodNameIsDifferent() {
      Method mockMethod = mock(Method.class);
      Object[] args = new Object[] {};

      LockContext context1 =
          new LockContext("test-lock", "TestClass.method1", mockMethod, args, void.class);
      LockContext context2 =
          new LockContext("test-lock", "TestClass.method2", mockMethod, args, void.class);

      assertNotEquals(context1, context2);
    }
  }

  @Nested
  @DisplayName("Record toString Tests")
  class RecordToStringTests {

    @Test
    @DisplayName("Should include all fields in toString")
    void shouldIncludeAllFieldsInToString() {
      Method mockMethod = mock(Method.class);
      Object[] args = new Object[] {};

      LockContext context =
          new LockContext("test-lock", "TestClass.testMethod", mockMethod, args, String.class);
      String str = context.toString();

      assertTrue(str.contains("test-lock"));
      assertTrue(str.contains("TestClass.testMethod"));
    }
  }
}
