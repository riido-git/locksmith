package in.riido.locksmith.handler.ratelimit;

import static org.junit.jupiter.api.Assertions.*;

import in.riido.locksmith.models.RateLimitContext;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("RateLimitReturnDefaultHandler Tests")
class RateLimitReturnDefaultHandlerTest {

  private RateLimitReturnDefaultHandler handler;

  @BeforeEach
  void setUp() {
    handler = new RateLimitReturnDefaultHandler();
  }

  @Nested
  @DisplayName("Primitive Type Tests")
  class PrimitiveTypeTests {

    @Test
    @DisplayName("Should return 0 for int return type")
    void shouldReturnZeroForInt() throws NoSuchMethodException {
      Method method = TestService.class.getMethod("intMethod");
      RateLimitContext context =
          new RateLimitContext("key", "intMethod", method, new Object[] {}, int.class);

      Object result = handler.handle(context);

      assertEquals(0, result);
    }

    @Test
    @DisplayName("Should return 0L for long return type")
    void shouldReturnZeroForLong() throws NoSuchMethodException {
      Method method = TestService.class.getMethod("longMethod");
      RateLimitContext context =
          new RateLimitContext("key", "longMethod", method, new Object[] {}, long.class);

      Object result = handler.handle(context);

      assertEquals(0L, result);
    }

    @Test
    @DisplayName("Should return 0.0 for double return type")
    void shouldReturnZeroForDouble() throws NoSuchMethodException {
      Method method = TestService.class.getMethod("doubleMethod");
      RateLimitContext context =
          new RateLimitContext("key", "doubleMethod", method, new Object[] {}, double.class);

      Object result = handler.handle(context);

      assertEquals(0.0, result);
    }

    @Test
    @DisplayName("Should return 0.0f for float return type")
    void shouldReturnZeroForFloat() throws NoSuchMethodException {
      Method method = TestService.class.getMethod("floatMethod");
      RateLimitContext context =
          new RateLimitContext("key", "floatMethod", method, new Object[] {}, float.class);

      Object result = handler.handle(context);

      assertEquals(0.0f, result);
    }

    @Test
    @DisplayName("Should return false for boolean return type")
    void shouldReturnFalseForBoolean() throws NoSuchMethodException {
      Method method = TestService.class.getMethod("booleanMethod");
      RateLimitContext context =
          new RateLimitContext("key", "booleanMethod", method, new Object[] {}, boolean.class);

      Object result = handler.handle(context);

      assertEquals(false, result);
    }

    @Test
    @DisplayName("Should return 0 for short return type")
    void shouldReturnZeroForShort() throws NoSuchMethodException {
      Method method = TestService.class.getMethod("shortMethod");
      RateLimitContext context =
          new RateLimitContext("key", "shortMethod", method, new Object[] {}, short.class);

      Object result = handler.handle(context);

      assertEquals((short) 0, result);
    }

    @Test
    @DisplayName("Should return 0 for byte return type")
    void shouldReturnZeroForByte() throws NoSuchMethodException {
      Method method = TestService.class.getMethod("byteMethod");
      RateLimitContext context =
          new RateLimitContext("key", "byteMethod", method, new Object[] {}, byte.class);

      Object result = handler.handle(context);

      assertEquals((byte) 0, result);
    }

    @Test
    @DisplayName("Should return null character for char return type")
    void shouldReturnNullCharForChar() throws NoSuchMethodException {
      Method method = TestService.class.getMethod("charMethod");
      RateLimitContext context =
          new RateLimitContext("key", "charMethod", method, new Object[] {}, char.class);

      Object result = handler.handle(context);

      assertEquals('\u0000', result);
    }
  }

  @Nested
  @DisplayName("Object Type Tests")
  class ObjectTypeTests {

    @Test
    @DisplayName("Should return null for String return type")
    void shouldReturnNullForString() throws NoSuchMethodException {
      Method method = TestService.class.getMethod("stringMethod");
      RateLimitContext context =
          new RateLimitContext("key", "stringMethod", method, new Object[] {}, String.class);

      Object result = handler.handle(context);

      assertNull(result);
    }

    @Test
    @DisplayName("Should return null for Object return type")
    void shouldReturnNullForObject() throws NoSuchMethodException {
      Method method = TestService.class.getMethod("objectMethod");
      RateLimitContext context =
          new RateLimitContext("key", "objectMethod", method, new Object[] {}, Object.class);

      Object result = handler.handle(context);

      assertNull(result);
    }

    @Test
    @DisplayName("Should return null for custom class return type")
    void shouldReturnNullForCustomClass() throws NoSuchMethodException {
      Method method = TestService.class.getMethod("customMethod");
      RateLimitContext context =
          new RateLimitContext("key", "customMethod", method, new Object[] {}, CustomClass.class);

      Object result = handler.handle(context);

      assertNull(result);
    }
  }

  @Nested
  @DisplayName("Void Type Tests")
  class VoidTypeTests {

    @Test
    @DisplayName("Should return null for void return type")
    void shouldReturnNullForVoid() throws NoSuchMethodException {
      Method method = TestService.class.getMethod("voidMethod");
      RateLimitContext context =
          new RateLimitContext("key", "voidMethod", method, new Object[] {}, void.class);

      Object result = handler.handle(context);

      assertNull(result);
    }

    @Test
    @DisplayName("Should return null for Void wrapper return type")
    void shouldReturnNullForVoidWrapper() throws NoSuchMethodException {
      Method method = TestService.class.getMethod("voidWrapperMethod");
      RateLimitContext context =
          new RateLimitContext("key", "voidWrapperMethod", method, new Object[] {}, Void.class);

      Object result = handler.handle(context);

      assertNull(result);
    }
  }

  @Nested
  @DisplayName("Collection Type Tests")
  class CollectionTypeTests {

    @Test
    @DisplayName("Should return null for List return type")
    void shouldReturnNullForList() throws NoSuchMethodException {
      Method method = TestService.class.getMethod("listMethod");
      RateLimitContext context =
          new RateLimitContext("key", "listMethod", method, new Object[] {}, List.class);

      Object result = handler.handle(context);

      assertNull(result);
    }

    @Test
    @DisplayName("Should return null for Map return type")
    void shouldReturnNullForMap() throws NoSuchMethodException {
      Method method = TestService.class.getMethod("mapMethod");
      RateLimitContext context =
          new RateLimitContext("key", "mapMethod", method, new Object[] {}, Map.class);

      Object result = handler.handle(context);

      assertNull(result);
    }
  }

  @Nested
  @DisplayName("Optional Type Tests")
  class OptionalTypeTests {

    @Test
    @DisplayName("Should return empty Optional for Optional return type")
    void shouldReturnEmptyOptional() throws NoSuchMethodException {
      Method method = TestService.class.getMethod("optionalMethod");
      RateLimitContext context =
          new RateLimitContext("key", "optionalMethod", method, new Object[] {}, Optional.class);

      Object result = handler.handle(context);

      assertEquals(Optional.empty(), result);
    }
  }

  // Test service for reflection
  public static class TestService {
    public int intMethod() {
      return 1;
    }

    public long longMethod() {
      return 1L;
    }

    public double doubleMethod() {
      return 1.0;
    }

    public float floatMethod() {
      return 1.0f;
    }

    public boolean booleanMethod() {
      return true;
    }

    public short shortMethod() {
      return 1;
    }

    public byte byteMethod() {
      return 1;
    }

    public char charMethod() {
      return 'a';
    }

    public String stringMethod() {
      return "test";
    }

    public Object objectMethod() {
      return new Object();
    }

    public CustomClass customMethod() {
      return new CustomClass();
    }

    public void voidMethod() {}

    public Void voidWrapperMethod() {
      return null;
    }

    public List<String> listMethod() {
      return List.of();
    }

    public Map<String, Object> mapMethod() {
      return Map.of();
    }

    public Optional<String> optionalMethod() {
      return Optional.of("test");
    }
  }

  public static class CustomClass {}
}
