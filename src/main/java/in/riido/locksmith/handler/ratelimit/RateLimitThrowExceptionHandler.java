package in.riido.locksmith.handler.ratelimit;

import in.riido.locksmith.exception.RateLimitExceededException;
import in.riido.locksmith.handler.RateLimitSkipHandler;
import in.riido.locksmith.models.RateLimitContext;
import org.jspecify.annotations.NonNull;

/**
 * A {@link RateLimitSkipHandler} that throws {@link RateLimitExceededException} when a rate limit
 * is exceeded. This is the default handler used by {@link in.riido.locksmith.RateLimit}.
 *
 * @author Garvit Joshi
 * @since 3.0.0
 */
public class RateLimitThrowExceptionHandler implements RateLimitSkipHandler {

  /** Default constructor. */
  public RateLimitThrowExceptionHandler() {}

  @Override
  public Object handle(@NonNull RateLimitContext context) {
    throw new RateLimitExceededException(context.rateLimitKey(), context.methodName());
  }
}
