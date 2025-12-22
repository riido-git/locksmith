package in.riido.locksmith.handler;

/**
 * A {@link LockSkipHandler} that returns default values when a lock cannot be acquired.
 *
 * <ul>
 *   <li>Returns {@code null} for object types and {@code void}/{@code Void}
 *   <li>Returns {@code false} for {@code boolean}/{@code Boolean}
 *   <li>Returns {@code 0} for numeric primitives and their wrapper types ({@code int}/{@code
 *       Integer}, {@code long}/{@code Long}, {@code double}/{@code Double}, etc.)
 *   <li>Returns {@code '\u0000'} for {@code char}/{@code Character}
 * </ul>
 *
 * @author Garvit Joshi
 * @since 1.2.0
 */
public class ReturnDefaultHandler implements LockSkipHandler {

  /** Default constructor. */
  public ReturnDefaultHandler() {}

  @Override
  public Object handle(LockContext context) {
    Class<?> returnType = context.returnType();
    if (returnType == void.class || returnType == Void.class) return null;
    if (returnType == boolean.class || returnType == Boolean.class) return false;
    if (returnType == int.class || returnType == Integer.class) return 0;
    if (returnType == long.class || returnType == Long.class) return 0L;
    if (returnType == double.class || returnType == Double.class) return 0.0d;
    if (returnType == float.class || returnType == Float.class) return 0.0f;
    if (returnType == byte.class || returnType == Byte.class) return (byte) 0;
    if (returnType == short.class || returnType == Short.class) return (short) 0;
    if (returnType == char.class || returnType == Character.class) return '\u0000';
    return null;
  }
}
