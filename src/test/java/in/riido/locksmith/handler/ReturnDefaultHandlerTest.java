package in.riido.locksmith.handler;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.lang.reflect.Method;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("ReturnDefaultHandler Tests")
class ReturnDefaultHandlerTest {

  private ReturnDefaultHandler handler;
  private Method mockMethod;

  @BeforeEach
  void setUp() {
    handler = new ReturnDefaultHandler();
    mockMethod = mock(Method.class);
  }

  private LockContext createContext(Class<?> returnType) {
    return new LockContext(
        "test-lock", "TestClass.testMethod", mockMethod, new Object[] {}, returnType);
  }

  @Nested
  @DisplayName("Void Return Type Tests")
  class VoidReturnTypeTests {

    @Test
    @DisplayName("Should return null for void primitive")
    void shouldReturnNullForVoidPrimitive() {
      LockContext context = createContext(void.class);

      Object result = handler.handle(context);

      assertNull(result);
    }

    @Test
    @DisplayName("Should return null for Void wrapper")
    void shouldReturnNullForVoidWrapper() {
      LockContext context = createContext(Void.class);

      Object result = handler.handle(context);

      assertNull(result);
    }
  }

  @Nested
  @DisplayName("Boolean Return Type Tests")
  class BooleanReturnTypeTests {

    @Test
    @DisplayName("Should return false for boolean primitive")
    void shouldReturnFalseForBooleanPrimitive() {
      LockContext context = createContext(boolean.class);

      Object result = handler.handle(context);

      assertEquals(false, result);
    }

    @Test
    @DisplayName("Should return false for Boolean wrapper")
    void shouldReturnFalseForBooleanWrapper() {
      LockContext context = createContext(Boolean.class);

      Object result = handler.handle(context);

      assertEquals(false, result);
    }
  }

  @Nested
  @DisplayName("Integer Return Type Tests")
  class IntegerReturnTypeTests {

    @Test
    @DisplayName("Should return 0 for int primitive")
    void shouldReturnZeroForIntPrimitive() {
      LockContext context = createContext(int.class);

      Object result = handler.handle(context);

      assertEquals(0, result);
    }

    @Test
    @DisplayName("Should return 0 for Integer wrapper")
    void shouldReturnZeroForIntegerWrapper() {
      LockContext context = createContext(Integer.class);

      Object result = handler.handle(context);

      assertEquals(0, result);
    }
  }

  @Nested
  @DisplayName("Long Return Type Tests")
  class LongReturnTypeTests {

    @Test
    @DisplayName("Should return 0L for long primitive")
    void shouldReturnZeroForLongPrimitive() {
      LockContext context = createContext(long.class);

      Object result = handler.handle(context);

      assertEquals(0L, result);
    }

    @Test
    @DisplayName("Should return 0L for Long wrapper")
    void shouldReturnZeroForLongWrapper() {
      LockContext context = createContext(Long.class);

      Object result = handler.handle(context);

      assertEquals(0L, result);
    }
  }

  @Nested
  @DisplayName("Double Return Type Tests")
  class DoubleReturnTypeTests {

    @Test
    @DisplayName("Should return 0.0d for double primitive")
    void shouldReturnZeroForDoublePrimitive() {
      LockContext context = createContext(double.class);

      Object result = handler.handle(context);

      assertEquals(0.0d, result);
    }

    @Test
    @DisplayName("Should return 0.0d for Double wrapper")
    void shouldReturnZeroForDoubleWrapper() {
      LockContext context = createContext(Double.class);

      Object result = handler.handle(context);

      assertEquals(0.0d, result);
    }
  }

  @Nested
  @DisplayName("Float Return Type Tests")
  class FloatReturnTypeTests {

    @Test
    @DisplayName("Should return 0.0f for float primitive")
    void shouldReturnZeroForFloatPrimitive() {
      LockContext context = createContext(float.class);

      Object result = handler.handle(context);

      assertEquals(0.0f, result);
    }

    @Test
    @DisplayName("Should return 0.0f for Float wrapper")
    void shouldReturnZeroForFloatWrapper() {
      LockContext context = createContext(Float.class);

      Object result = handler.handle(context);

      assertEquals(0.0f, result);
    }
  }

  @Nested
  @DisplayName("Byte Return Type Tests")
  class ByteReturnTypeTests {

    @Test
    @DisplayName("Should return 0 for byte primitive")
    void shouldReturnZeroForBytePrimitive() {
      LockContext context = createContext(byte.class);

      Object result = handler.handle(context);

      assertEquals((byte) 0, result);
    }

    @Test
    @DisplayName("Should return 0 for Byte wrapper")
    void shouldReturnZeroForByteWrapper() {
      LockContext context = createContext(Byte.class);

      Object result = handler.handle(context);

      assertEquals((byte) 0, result);
    }
  }

  @Nested
  @DisplayName("Short Return Type Tests")
  class ShortReturnTypeTests {

    @Test
    @DisplayName("Should return 0 for short primitive")
    void shouldReturnZeroForShortPrimitive() {
      LockContext context = createContext(short.class);

      Object result = handler.handle(context);

      assertEquals((short) 0, result);
    }

    @Test
    @DisplayName("Should return 0 for Short wrapper")
    void shouldReturnZeroForShortWrapper() {
      LockContext context = createContext(Short.class);

      Object result = handler.handle(context);

      assertEquals((short) 0, result);
    }
  }

  @Nested
  @DisplayName("Character Return Type Tests")
  class CharacterReturnTypeTests {

    @Test
    @DisplayName("Should return null char for char primitive")
    void shouldReturnNullCharForCharPrimitive() {
      LockContext context = createContext(char.class);

      Object result = handler.handle(context);

      assertEquals('\u0000', result);
    }

    @Test
    @DisplayName("Should return null char for Character wrapper")
    void shouldReturnNullCharForCharacterWrapper() {
      LockContext context = createContext(Character.class);

      Object result = handler.handle(context);

      assertEquals('\u0000', result);
    }
  }

  @Nested
  @DisplayName("Object Return Type Tests")
  class ObjectReturnTypeTests {

    @Test
    @DisplayName("Should return null for String")
    void shouldReturnNullForString() {
      LockContext context = createContext(String.class);

      Object result = handler.handle(context);

      assertNull(result);
    }

    @Test
    @DisplayName("Should return null for Object")
    void shouldReturnNullForObject() {
      LockContext context = createContext(Object.class);

      Object result = handler.handle(context);

      assertNull(result);
    }

    @Test
    @DisplayName("Should return null for custom class")
    void shouldReturnNullForCustomClass() {
      LockContext context = createContext(CustomClass.class);

      Object result = handler.handle(context);

      assertNull(result);
    }

    @Test
    @DisplayName("Should return null for interface type")
    void shouldReturnNullForInterfaceType() {
      LockContext context = createContext(Runnable.class);

      Object result = handler.handle(context);

      assertNull(result);
    }

    @Test
    @DisplayName("Should return null for array type")
    void shouldReturnNullForArrayType() {
      LockContext context = createContext(String[].class);

      Object result = handler.handle(context);

      assertNull(result);
    }
  }

  @Nested
  @DisplayName("Handler Instantiation Tests")
  class HandlerInstantiationTests {

    @Test
    @DisplayName("Should be instantiable with no-arg constructor")
    void shouldBeInstantiableWithNoArgConstructor() {
      ReturnDefaultHandler handler = new ReturnDefaultHandler();

      assertNotNull(handler);
    }

    @Test
    @DisplayName("Should implement LockSkipHandler interface")
    void shouldImplementLockSkipHandlerInterface() {
      assertTrue(LockSkipHandler.class.isAssignableFrom(ReturnDefaultHandler.class));
    }
  }

  private static class CustomClass {}
}
