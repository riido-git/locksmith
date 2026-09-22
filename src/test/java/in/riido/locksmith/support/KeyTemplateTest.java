package in.riido.locksmith.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import in.riido.locksmith.LocksmithConfigurationException;
import java.lang.reflect.Method;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.context.expression.MethodBasedEvaluationContext;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.expression.Expression;
import org.springframework.expression.ParseException;

@DisplayName("KeyTemplate")
class KeyTemplateTest {

  static class Order {
    private final String id;

    Order(String id) {
      this.id = id;
    }

    public String getId() {
      return id;
    }
  }

  static class Fixture {
    void place(String userId, Order order) {}

    void tag(List<String> tags) {}
  }

  private static final Method PLACE = method();
  private static final String PLACE_NAME = Fixture.class.getName() + ".place";

  private static Method method() {
    try {
      return Fixture.class.getDeclaredMethod("place", String.class, Order.class);
    } catch (NoSuchMethodException e) {
      throw new IllegalStateException(e);
    }
  }

  private static String evaluate(String template, Object... args) {
    return KeyTemplate.evaluate(KeyTemplate.parse(template), PLACE, args);
  }

  @Nested
  @DisplayName("evaluate")
  class Evaluate {

    @Test
    @DisplayName("literal-only template returns the text unchanged")
    void literalOnly() {
      assertThat(evaluate("scheduler:cleanup", "u1", null)).isEqualTo("scheduler:cleanup");
    }

    @Test
    @DisplayName("mixed text and island concatenates the text and the variable")
    void mixedTextAndIsland() {
      assertThat(evaluate("user:#{#userId}", "u1", null)).isEqualTo("user:u1");
    }

    @Test
    @DisplayName("island-only template returns the expression value")
    void islandOnly() {
      assertThat(evaluate("#{'user-' + #userId}", "u1", null)).isEqualTo("user-u1");
    }

    @Test
    @DisplayName("#p0 and #a1 resolve to arguments by index")
    void indexedVariables() {
      assertThat(evaluate("#{#p0}:#{#a1.id}", "u1", new Order("o7"))).isEqualTo("u1:o7");
    }

    @Test
    @DisplayName("property path #order.id resolves through the getter")
    void propertyPath() {
      assertThat(evaluate("order:#{#order.id}", "u1", new Order("o7"))).isEqualTo("order:o7");
    }

    @Test
    @DisplayName("null result is rejected naming the method and the template")
    void nullResultRejected() {
      assertThatThrownBy(() -> evaluate("#{#userId}", null, null))
          .isInstanceOf(LocksmithConfigurationException.class)
          .hasMessageContaining("place")
          .hasMessageContaining("[#{#userId}]");
    }

    @Test
    @DisplayName("blank result is rejected naming the method and the template")
    void blankResultRejected() {
      assertThatThrownBy(() -> evaluate("#{#userId}", "  ", null))
          .isInstanceOf(LocksmithConfigurationException.class)
          .hasMessageContaining("place")
          .hasMessageContaining("[#{#userId}]");
    }

    @Test
    @DisplayName("null island in a mixed template is rejected naming the part")
    void nullPartRejected() {
      assertThatThrownBy(() -> evaluate("order:#{#userId}", null, null))
          .isInstanceOf(LocksmithConfigurationException.class)
          .hasMessage(
              "Key template [order:#{#userId}] on "
                  + PLACE_NAME
                  + ": part #{#userId} resolved to null");
    }

    @Test
    @DisplayName("empty island in a mixed template is rejected as blank")
    void emptyPartRejected() {
      assertThatThrownBy(() -> evaluate("order:#{#userId}", "", null))
          .isInstanceOf(LocksmithConfigurationException.class)
          .hasMessage(
              "Key template [order:#{#userId}] on "
                  + PLACE_NAME
                  + ": part #{#userId} resolved to a blank value");
    }

    @Test
    @DisplayName("blank island in a mixed template is rejected as blank")
    void blankPartRejected() {
      assertThatThrownBy(() -> evaluate("order:#{#userId}", "  ", null))
          .isInstanceOf(LocksmithConfigurationException.class)
          .hasMessage(
              "Key template [order:#{#userId}] on "
                  + PLACE_NAME
                  + ": part #{#userId} resolved to a blank value");
    }

    @Test
    @DisplayName("null second island is rejected naming that island")
    void nullSecondPartRejected() {
      assertThatThrownBy(() -> evaluate("#{#userId}:#{#order.id}", "u1", new Order(null)))
          .isInstanceOf(LocksmithConfigurationException.class)
          .hasMessage(
              "Key template [#{#userId}:#{#order.id}] on "
                  + PLACE_NAME
                  + ": part #{#order.id} resolved to null");
    }

    @Test
    @DisplayName("non-null islands render exactly as Spring's composite expression does")
    void rendersLikeComposite() {
      Expression expression = KeyTemplate.parse("order:#{#userId}-#{#order.id}:#{#p0.length()}");
      Object[] args = {"u1", new Order("o7")};
      String spring =
          expression.getValue(
              new MethodBasedEvaluationContext(
                  null, PLACE, args, DefaultParameterNameDiscoverer.getSharedInstance()),
              String.class);

      assertThat(KeyTemplate.evaluate(expression, PLACE, args))
          .isEqualTo(spring)
          .isEqualTo("order:u1-o7:2");
    }
  }

  @Nested
  @DisplayName("parse")
  class Parse {

    @Test
    @DisplayName("invalid SpEL throws Spring's ParseException")
    void invalidSpelThrowsParseException() {
      assertThatThrownBy(() -> KeyTemplate.parse("user:#{#userId +}"))
          .isInstanceOf(ParseException.class);
    }
  }

  @Nested
  @DisplayName("validateVariables")
  class ValidateVariables {

    private void validate(String template) {
      KeyTemplate.validateVariables(KeyTemplate.parse(template), PLACE);
    }

    @Test
    @DisplayName("literal-only template has no variables and passes")
    void literalOnlyPasses() {
      assertThatCode(() -> validate("scheduler:cleanup")).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("parameter names, property paths, #pN and #aN in range pass")
    void knownVariablesPass() {
      Expression expression =
          KeyTemplate.parse("#{#userId}:#{#order.id}:#{#p0}:#{#a1}:#{'x' + #p1.id}");

      assertThatCode(() -> KeyTemplate.validateVariables(expression, PLACE))
          .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("missing parameter name fails with class, method, variable and -parameters hint")
    void missingParameterNameFails() {
      assertThatThrownBy(() -> validate("user:#{#customerId}"))
          .isInstanceOf(LocksmithConfigurationException.class)
          .hasMessageContaining("[user:#{#customerId}]")
          .hasMessageContaining(Fixture.class.getName())
          .hasMessageContaining("place")
          .hasMessageContaining("#customerId")
          .hasMessageContaining("compile with -parameters or use #p0");
    }

    @Test
    @DisplayName("#p2 on a two-parameter method fails as out of range")
    void indexOutOfRangeFails() {
      assertThatThrownBy(() -> validate("#{#p2}"))
          .isInstanceOf(LocksmithConfigurationException.class)
          .hasMessageContaining("#p2");
    }

    @Test
    @DisplayName("#p01 is not an index variable and fails")
    void leadingZeroIndexFails() {
      assertThatThrownBy(() -> validate("#{#p01}"))
          .isInstanceOf(LocksmithConfigurationException.class)
          .hasMessageContaining("#p01");
    }

    @Test
    @DisplayName("#this inside a selection over a list parameter passes")
    void thisInSelectionPasses() throws NoSuchMethodException {
      Method tag = Fixture.class.getDeclaredMethod("tag", List.class);

      assertThatCode(
              () ->
                  KeyTemplate.validateVariables(KeyTemplate.parse("#{#p0.^[#this != null]}"), tag))
          .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("#root fails as an unknown variable")
    void rootFails() {
      assertThatThrownBy(() -> validate("#{#root}"))
          .isInstanceOf(LocksmithConfigurationException.class)
          .hasMessageContaining("#root");
    }
  }
}
