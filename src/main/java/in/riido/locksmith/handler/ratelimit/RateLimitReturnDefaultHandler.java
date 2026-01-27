package in.riido.locksmith.handler.ratelimit;

import in.riido.locksmith.handler.DefaultValueResolver;
import in.riido.locksmith.handler.RateLimitSkipHandler;
import in.riido.locksmith.models.RateLimitContext;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * A {@link RateLimitSkipHandler} that returns default values when a rate limit is exceeded.
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
 * @since 3.0.0
 */
public class RateLimitReturnDefaultHandler implements RateLimitSkipHandler {

  /** Default constructor. */
  public RateLimitReturnDefaultHandler() {}

  @Override
  @Nullable
  public Object handle(@NonNull RateLimitContext context) {
    return DefaultValueResolver.resolve(context.returnType());
  }
}
