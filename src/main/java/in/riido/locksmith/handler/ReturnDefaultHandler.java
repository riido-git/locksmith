package in.riido.locksmith.handler;

/**
 * A {@link LockSkipHandler} that returns default values when a lock cannot be acquired.
 *
 * <ul>
 *   <li>Returns {@code null} for object types and {@code void}
 *   <li>Returns {@code false} for {@code boolean}
 *   <li>Returns {@code 0} for numeric primitives ({@code int}, {@code long}, {@code double}, etc.)
 *   <li>Returns {@code '\u0000'} for {@code char}
 * </ul>
 *
 * @author Garvit Joshi
 * @since 1.3.0
 */
public class ReturnDefaultHandler implements LockSkipHandler {

  @Override
  public Object handle(LockContext context) {
    Class<?> returnType = context.returnType();
    if (returnType == void.class) return null;
    if (returnType == boolean.class) return false;
    if (returnType == int.class) return 0;
    if (returnType == long.class) return 0L;
    if (returnType == double.class) return 0.0d;
    if (returnType == float.class) return 0.0f;
    if (returnType == byte.class) return (byte) 0;
    if (returnType == short.class) return (short) 0;
    if (returnType == char.class) return '\u0000';
    return null;
  }
}
