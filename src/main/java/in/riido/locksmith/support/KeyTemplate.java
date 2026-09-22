package in.riido.locksmith.support;

import in.riido.locksmith.LocksmithConfigurationException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.context.expression.MethodBasedEvaluationContext;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.expression.Expression;
import org.springframework.expression.ParseException;
import org.springframework.expression.ParserContext;
import org.springframework.expression.common.CompositeStringExpression;
import org.springframework.expression.common.LiteralExpression;
import org.springframework.expression.spel.SpelNode;
import org.springframework.expression.spel.ast.VariableReference;
import org.springframework.expression.spel.standard.SpelExpression;
import org.springframework.expression.spel.standard.SpelExpressionParser;

/**
 * Parses, checks and evaluates key templates: literal text with {@code #{...}} SpEL islands whose
 * variables are the method parameters by name, {@code #pN} or {@code #aN}, and {@code #this} inside
 * a selection or projection.
 */
public final class KeyTemplate {

  /** Hint added to the message when a template variable does not match a parameter. */
  private static final String PARAMETERS_HINT = "compile with -parameters or use #p0";

  private static final SpelExpressionParser PARSER = new SpelExpressionParser();

  /** {@code #pN} or {@code #aN} as {@link MethodBasedEvaluationContext} defines them. */
  private static final Pattern INDEXED_VARIABLE = Pattern.compile("[pa](0|[1-9][0-9]{0,8})");

  /** {@code #this}, the current element inside a selection or projection. */
  private static final String THIS_VARIABLE = "this";

  private KeyTemplate() {}

  /**
   * Parses a key template in template mode.
   *
   * @param template the template, for example {@code "user:#{#userId}"}
   * @return the parsed expression
   * @throws ParseException if the template is not valid SpEL
   */
  public static @NonNull Expression parse(@NonNull String template) {
    return PARSER.parseExpression(template, ParserContext.TEMPLATE_EXPRESSION);
  }

  /**
   * Evaluates a parsed template against one invocation of a method.
   *
   * @param expression the parsed template
   * @param method the invoked method
   * @param args the invocation arguments
   * @return the resolved key, never blank
   * @throws LocksmithConfigurationException if the template, or one of its {@code #{...}} islands,
   *     resolves to null or a blank string
   * @throws org.springframework.expression.EvaluationException if evaluation fails
   */
  public static @NonNull String evaluate(
      @NonNull Expression expression, @NonNull Method method, @Nullable Object @NonNull [] args) {
    MethodBasedEvaluationContext context =
        new MethodBasedEvaluationContext(
            null, method, args, DefaultParameterNameDiscoverer.getSharedInstance());
    String key =
        expression instanceof CompositeStringExpression composite
            ? evaluateParts(composite, method, context)
            : expression.getValue(context, String.class);
    if (key == null || key.isBlank()) {
      throw new LocksmithConfigurationException(
          "Key template ["
              + describe(expression)
              + "] on "
              + describe(method)
              + " resolved to a null or blank key");
    }
    return key;
  }

  /**
   * Concatenates the parts the way {@link CompositeStringExpression#getValue} does, but rejects an
   * island that resolves to null or blank: the composite would skip it or keep it, and every such
   * call would then share one key.
   */
  private static @NonNull String evaluateParts(
      @NonNull CompositeStringExpression composite,
      @NonNull Method method,
      @NonNull MethodBasedEvaluationContext context) {
    StringBuilder key = new StringBuilder();
    for (Expression part : composite.getExpressions()) {
      String value = part.getValue(context, String.class);
      if (!(part instanceof LiteralExpression) && (value == null || value.isBlank())) {
        throw new LocksmithConfigurationException(
            "Key template ["
                + describe(composite)
                + "] on "
                + describe(method)
                + ": part "
                + describe(part)
                + (value == null ? " resolved to null" : " resolved to a blank value"));
      }
      if (value != null) {
        key.append(value);
      }
    }
    return key.toString();
  }

  /**
   * Checks that every variable in a parsed template is a parameter name of the method, {@code #pN}
   * / {@code #aN} with {@code N} below the parameter count, or {@code #this}, which is allowed
   * inside filters and projections. {@code #root} is rejected: there is no root object.
   *
   * @param expression the parsed template
   * @param method the annotated method
   * @throws LocksmithConfigurationException naming the class, the method and the variable if a
   *     variable does not match
   */
  public static void validateVariables(@NonNull Expression expression, @NonNull Method method) {
    String[] names = DefaultParameterNameDiscoverer.getSharedInstance().getParameterNames(method);
    List<String> parameterNames = names == null ? List.of() : Arrays.asList(names);
    for (String variable : variables(expression)) {
      if (!variable.equals(THIS_VARIABLE)
          && !parameterNames.contains(variable)
          && !isIndexInRange(variable, method)) {
        throw new LocksmithConfigurationException(
            "Key template ["
                + describe(expression)
                + "] on "
                + describe(method)
                + " uses variable #"
                + variable
                + " which is not a parameter of the method; "
                + PARAMETERS_HINT);
      }
    }
  }

  private static boolean isIndexInRange(@NonNull String variable, @NonNull Method method) {
    return INDEXED_VARIABLE.matcher(variable).matches()
        && Integer.parseInt(variable.substring(1)) < method.getParameterCount();
  }

  private static @NonNull List<String> variables(@NonNull Expression expression) {
    List<String> variables = new ArrayList<>();
    collect(expression, variables);
    return variables;
  }

  private static void collect(@NonNull Expression expression, @NonNull List<String> variables) {
    if (expression instanceof CompositeStringExpression composite) {
      for (Expression part : composite.getExpressions()) {
        collect(part, variables);
      }
    } else if (expression instanceof SpelExpression spel) {
      collect(spel.getAST(), variables);
    }
    // LiteralExpression has no variables.
  }

  private static void collect(@NonNull SpelNode node, @NonNull List<String> variables) {
    if (node instanceof VariableReference) {
      variables.add(node.toStringAST().substring(1));
    }
    for (int i = 0; i < node.getChildCount(); i++) {
      collect(node.getChild(i), variables);
    }
  }

  private static @NonNull String describe(@NonNull Method method) {
    return method.getDeclaringClass().getName() + "." + method.getName();
  }

  /**
   * Returns the template as written. An island-only template parses to a bare {@link
   * SpelExpression} whose string is the island body, so its braces are put back; composite and
   * literal expressions already carry the original text.
   */
  private static @NonNull String describe(@NonNull Expression expression) {
    return expression instanceof SpelExpression
        ? "#{" + expression.getExpressionString() + "}"
        : expression.getExpressionString();
  }
}
