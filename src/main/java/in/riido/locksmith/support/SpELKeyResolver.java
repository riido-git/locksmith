package in.riido.locksmith.support;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.context.expression.MethodBasedEvaluationContext;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.core.ParameterNameDiscoverer;
import org.springframework.expression.EvaluationContext;
import org.springframework.expression.Expression;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;

/**
 * Utility class for resolving lock/semaphore keys with SpEL support.
 *
 * <p>SpEL expressions must be wrapped in {@code #{...}} syntax. Keys without this wrapper are
 * treated as literal strings.
 *
 * <p>Examples:
 *
 * <ul>
 *   <li>{@code "#{#userId}"} - SpEL: evaluates to the value of userId parameter
 *   <li>{@code "#{#order.id}"} - SpEL: evaluates to order.id property
 *   <li>{@code "#{'prefix-' + #id}"} - SpEL: concatenation
 *   <li>{@code "my-key"} - Literal: used as-is
 *   <li>{@code "order#123"} - Literal: used as-is (# without wrapper is not SpEL)
 * </ul>
 *
 * <p>This class is thread-safe. Parsed SpEL expressions are cached for performance.
 *
 * @author Garvit Joshi
 * @since 2.0.0
 */
public final class SpELKeyResolver {

  private static final ExpressionParser EXPRESSION_PARSER = new SpelExpressionParser();
  private static final ParameterNameDiscoverer PARAMETER_NAME_DISCOVERER =
      new DefaultParameterNameDiscoverer();

  /**
   * Cache of parsed SpEL expressions keyed by the expression string (e.g., {@code "#userId"},
   * {@code "'user-' + #id"}). This cache is bounded by the number of unique SpEL expression strings
   * declared in annotations across the codebase, which is determined at compile time. For example,
   * {@code @DistributedLock(key = "#{#userId}")} always produces the same cache key ({@code
   * "#userId"}) regardless of how many different {@code userId} values are evaluated at runtime.
   */
  private static final Map<String, Expression> EXPRESSION_CACHE = new ConcurrentHashMap<>();

  private SpELKeyResolver() {
    // Utility class
  }

  /**
   * Resolves the key, evaluating SpEL expressions if present.
   *
   * <p>SpEL expressions must be wrapped in {@code #{...}} syntax: {@code #{#userId}}, {@code
   * #{'user-' + #id}}
   *
   * <p>Literal keys (without {@code #{...}}) are returned as-is and can contain any characters
   * including {@code #}: {@code order#123}, {@code item-#1}, {@code task#}
   *
   * @param keyExpression the key expression (literal or SpEL)
   * @param joinPoint the join point for accessing method and arguments
   * @return the resolved key string
   * @throws IllegalArgumentException if the SpEL expression evaluates to null or blank
   */
  @NonNull
  public static String resolve(
      @NonNull String keyExpression, @NonNull ProceedingJoinPoint joinPoint) {
    MethodSignature signature = (MethodSignature) joinPoint.getSignature();
    return resolve(keyExpression, signature.getMethod(), joinPoint.getArgs());
  }

  /**
   * Resolves the key, evaluating SpEL expressions if present.
   *
   * <p>SpEL expressions must be wrapped in {@code #{...}} syntax.
   *
   * @param keyExpression the key expression (literal or SpEL)
   * @param method the method being invoked
   * @param args the method arguments
   * @return the resolved key string
   * @throws IllegalArgumentException if the SpEL expression evaluates to null or blank
   */
  @NonNull
  public static String resolve(
      @NonNull String keyExpression, @NonNull Method method, @Nullable Object[] args) {
    if (keyExpression.startsWith("#{") && keyExpression.endsWith("}")) {
      return evaluateSpEL(keyExpression.substring(2, keyExpression.length() - 1), method, args);
    }
    return keyExpression;
  }

  /**
   * Evaluates a SpEL expression and returns the resolved key.
   *
   * <p>Parsed {@link Expression} objects are cached in {@link #EXPRESSION_CACHE} by the expression
   * string itself (not the evaluated result). This means the cache size is bounded by the number of
   * unique SpEL expressions in the codebase — typically one per annotated method. The same cached
   * {@link Expression} is re-evaluated with a fresh {@link EvaluationContext} on each invocation,
   * producing different resolved keys from different method arguments without growing the cache.
   *
   * @param spELExpression the SpEL expression to evaluate (without #{} wrapper)
   * @param method the method being invoked
   * @param args the method arguments
   * @return the resolved key string
   * @throws IllegalArgumentException if the expression evaluates to null or blank
   */
  @NonNull
  private static String evaluateSpEL(
      @NonNull String spELExpression, @NonNull Method method, @Nullable Object[] args) {
    EvaluationContext context =
        new MethodBasedEvaluationContext(null, method, args, PARAMETER_NAME_DISCOVERER);

    Expression expression =
        EXPRESSION_CACHE.computeIfAbsent(spELExpression, EXPRESSION_PARSER::parseExpression);

    Object result = expression.getValue(context);

    if (result == null) {
      throw new IllegalArgumentException(
          "SpEL expression '"
              + spELExpression
              + "' evaluated to null for method: "
              + method.getDeclaringClass().getSimpleName()
              + "."
              + method.getName());
    }

    String resolvedKey = result.toString();
    if (resolvedKey.isBlank()) {
      throw new IllegalArgumentException(
          "SpEL expression '"
              + spELExpression
              + "' evaluated to blank for method: "
              + method.getDeclaringClass().getSimpleName()
              + "."
              + method.getName());
    }

    return resolvedKey;
  }

  /**
   * Checks if the given key expression is a SpEL expression.
   *
   * @param keyExpression the key expression to check
   * @return true if the expression is wrapped in #{...}, false otherwise
   */
  public static boolean isSpELExpression(@NonNull String keyExpression) {
    return keyExpression.startsWith("#{") && keyExpression.endsWith("}");
  }
}
