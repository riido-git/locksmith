package in.riido.locksmith.handler;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Utility class for resolving default values based on return types.
 *
 * <p>This class provides a shared implementation for returning appropriate default values when a
 * method execution is skipped due to failed lock or semaphore acquisition.
 *
 * <p><b>Default Values:</b>
 *
 * <ul>
 *   <li>{@code null} for object types and {@code void}/{@code Void}
 *   <li>{@code false} for {@code boolean}/{@code Boolean}
 *   <li>{@code 0} for numeric primitives and their wrapper types ({@code int}/{@code Integer},
 *       {@code long}/{@code Long}, {@code double}/{@code Double}, etc.)
 *   <li>{@code '\u0000'} for {@code char}/{@code Character}
 * </ul>
 *
 * @author Garvit Joshi
 * @since 2.0.0
 */
public final class DefaultValueResolver {

  private DefaultValueResolver() {
    // Utility class - prevent instantiation
  }

  /**
   * Resolves the default value for a given return type.
   *
   * @param returnType the class representing the method's return type
   * @return the default value appropriate for the return type
   */
  @Nullable
  public static Object resolve(@NonNull Class<?> returnType) {
    if (returnType == void.class || returnType == Void.class) {
      return null;
    }
    if (returnType == boolean.class || returnType == Boolean.class) {
      return false;
    }
    if (returnType == int.class || returnType == Integer.class) {
      return 0;
    }
    if (returnType == long.class || returnType == Long.class) {
      return 0L;
    }
    if (returnType == double.class || returnType == Double.class) {
      return 0.0d;
    }
    if (returnType == float.class || returnType == Float.class) {
      return 0.0f;
    }
    if (returnType == byte.class || returnType == Byte.class) {
      return (byte) 0;
    }
    if (returnType == short.class || returnType == Short.class) {
      return (short) 0;
    }
    if (returnType == char.class || returnType == Character.class) {
      return '\u0000';
    }
    return null;
  }
}
