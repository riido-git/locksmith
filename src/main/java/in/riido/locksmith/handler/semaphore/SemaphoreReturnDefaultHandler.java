package in.riido.locksmith.handler.semaphore;

import in.riido.locksmith.handler.DefaultValueResolver;
import in.riido.locksmith.handler.SemaphoreSkipHandler;
import in.riido.locksmith.models.SemaphoreContext;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * A {@link SemaphoreSkipHandler} that returns default values when a semaphore permit cannot be
 * acquired.
 *
 * <p>Returns appropriate default values based on the method's return type:
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
 * @see DefaultValueResolver
 * @since 2.0.0
 */
public class SemaphoreReturnDefaultHandler implements SemaphoreSkipHandler {

  /** Default constructor. */
  public SemaphoreReturnDefaultHandler() {}

  @Override
  @Nullable
  public Object handle(@NonNull SemaphoreContext context) {
    return DefaultValueResolver.resolve(context.returnType());
  }
}
