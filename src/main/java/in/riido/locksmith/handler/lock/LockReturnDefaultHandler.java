package in.riido.locksmith.handler.lock;

import in.riido.locksmith.handler.DefaultValueResolver;
import in.riido.locksmith.handler.LockSkipHandler;
import in.riido.locksmith.models.LockContext;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * A {@link LockSkipHandler} that returns default values when a lock cannot be acquired.
 *
 * <p>Returns appropriate default values based on the method's return type:
 *
 * <ul>
 *   <li>{@code null} for object types and {@code void}/{@code Void}
 *   <li>{@code false} for {@code boolean}/{@code Boolean}
 *   <li>{@code 0} for numeric primitives and their wrapper types ({@code int}/{@code Integer},
 *       {@code long}/{@code Long}, {@code double}/{@code Double}, etc.)
 *   <li>{@code '\u0000'} for {@code char}/{@code Character}
 *   <li>{@code Optional.empty()} for {@code Optional} types
 * </ul>
 *
 * @author Garvit Joshi
 * @see DefaultValueResolver
 * @since 1.2.0
 */
public class LockReturnDefaultHandler implements LockSkipHandler {

  /** Default constructor. */
  public LockReturnDefaultHandler() {}

  @Override
  @Nullable
  public Object handle(@NonNull LockContext context) {
    return DefaultValueResolver.resolve(context.returnType());
  }
}
