package in.riido.locksmith.aspect;

import static org.junit.jupiter.api.Assertions.*;

import in.riido.locksmith.DistributedLock;
import in.riido.locksmith.support.SpELKeyResolver;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Comprehensive tests for SpEL key resolution using SpELKeyResolver.
 *
 * <p>Tests both the new recommended #{...} syntax and legacy syntax (with deprecation warnings).
 *
 * @see <a href="https://github.com/riido-git/locksmith/issues/13">GitHub Issue #13</a>
 */
@DisplayName("SpEL Key Resolution Tests")
class SpELKeyResolutionTest {

  /** Test class with various SpEL annotation examples. */
  public static class TestClass {
    @DistributedLock(key = "#{#userId}")
    public void processUser(String userId) {}

    @DistributedLock(key = "#{#orderId}")
    public void processOrder(Long orderId) {}

    @DistributedLock(key = "#{'user-' + #id}")
    public void processWithPrefix(Long id) {}

    @DistributedLock(key = "#{#user.name}")
    public void updateUser(User user) {}

    @DistributedLock(key = "#{#user.email}")
    public void processUserEmail(User user) {}

    @DistributedLock(key = "#{#p0}")
    public void processWithP0(String value) {}

    @DistributedLock(key = "#{#a0}")
    public void processWithA0(String value) {}

    @DistributedLock(key = "#{#userId + '-' + #role}")
    public void processUserRole(String userId, String role) {}

    @DistributedLock(key = "#{T(java.lang.String).valueOf(#id)}")
    public void processWithStaticMethod(Integer id) {}

    @DistributedLock(key = "#{#users.size()}")
    public void processUsersList(List<User> users) {}

    @DistributedLock(key = "#{#users[0].name}")
    public void processFirstUser(List<User> users) {}

    @DistributedLock(key = "#{#id > 100 ? 'large' : 'small'}")
    public void processConditional(int id) {}

    @DistributedLock(key = "order#123")
    public void processLiteralWithHash() {}

    @DistributedLock(key = "item-#1")
    public void processLiteralWithHashPrefix() {}

    @DistributedLock(key = "task#end")
    public void processLiteralWithHashSuffix() {}

    @DistributedLock(key = "prefix#middle#suffix")
    public void processMultipleHashes() {}

    @DistributedLock(key = "valid:key:123")
    public void processLiteralNoHash() {}

    public record User(String name, String email, int age) {}
  }

  @Nested
  @DisplayName("New #{...} Syntax Tests (Recommended)")
  class NewSyntaxTests {

    @Test
    @DisplayName("Should resolve simple parameter reference #{#userId}")
    void shouldResolveSimpleParameter() throws Exception {
      Method method = TestClass.class.getMethod("processUser", String.class);
      Object[] args = new Object[] {"user123"};

      String result = SpELKeyResolver.resolve("#{#userId}", method, args);

      assertEquals("user123", result);
    }

    @Test
    @DisplayName("Should resolve Long parameter #{#orderId}")
    void shouldResolveLongParameter() throws Exception {
      Method method = TestClass.class.getMethod("processOrder", Long.class);
      Object[] args = new Object[] {42L};

      String result = SpELKeyResolver.resolve("#{#orderId}", method, args);

      assertEquals("42", result);
    }

    @Test
    @DisplayName("Should resolve string concatenation #{'user-' + #id}")
    void shouldResolveStringConcatenation() throws Exception {
      Method method = TestClass.class.getMethod("processWithPrefix", Long.class);
      Object[] args = new Object[] {99L};

      String result = SpELKeyResolver.resolve("#{'user-' + #id}", method, args);

      assertEquals("user-99", result);
    }

    @Test
    @DisplayName("Should resolve object property #{#user.name}")
    void shouldResolveObjectProperty() throws Exception {
      Method method = TestClass.class.getMethod("updateUser", TestClass.User.class);
      Object[] args = new Object[] {new TestClass.User("Alice", "alice@example.com", 30)};

      String result = SpELKeyResolver.resolve("#{#user.name}", method, args);

      assertEquals("Alice", result);
    }

    @Test
    @DisplayName("Should resolve nested property #{#user.email}")
    void shouldResolveNestedProperty() throws Exception {
      Method method = TestClass.class.getMethod("processUserEmail", TestClass.User.class);
      Object[] args = new Object[] {new TestClass.User("Bob", "bob@test.com", 25)};

      String result = SpELKeyResolver.resolve("#{#user.email}", method, args);

      assertEquals("bob@test.com", result);
    }

    @Test
    @DisplayName("Should resolve parameter by position #{#p0}")
    void shouldResolveByPositionP0() throws Exception {
      Method method = TestClass.class.getMethod("processWithP0", String.class);
      Object[] args = new Object[] {"value-p0"};

      String result = SpELKeyResolver.resolve("#{#p0}", method, args);

      assertEquals("value-p0", result);
    }

    @Test
    @DisplayName("Should resolve parameter by position #{#a0}")
    void shouldResolveByPositionA0() throws Exception {
      Method method = TestClass.class.getMethod("processWithA0", String.class);
      Object[] args = new Object[] {"value-a0"};

      String result = SpELKeyResolver.resolve("#{#a0}", method, args);

      assertEquals("value-a0", result);
    }

    @Test
    @DisplayName("Should resolve multiple parameters #{#userId + '-' + #role}")
    void shouldResolveMultipleParameters() throws Exception {
      Method method = TestClass.class.getMethod("processUserRole", String.class, String.class);
      Object[] args = new Object[] {"user456", "admin"};

      String result = SpELKeyResolver.resolve("#{#userId + '-' + #role}", method, args);

      assertEquals("user456-admin", result);
    }

    @Test
    @DisplayName("Should resolve static method call #{T(String).valueOf(#id)}")
    void shouldResolveStaticMethodCall() throws Exception {
      Method method = TestClass.class.getMethod("processWithStaticMethod", Integer.class);
      Object[] args = new Object[] {789};

      String result = SpELKeyResolver.resolve("#{T(java.lang.String).valueOf(#id)}", method, args);

      assertEquals("789", result);
    }

    @Test
    @DisplayName("Should resolve collection method #{#users.size()}")
    void shouldResolveCollectionMethod() throws Exception {
      Method method = TestClass.class.getMethod("processUsersList", List.class);
      List<TestClass.User> users =
          Arrays.asList(
              new TestClass.User("Alice", "alice@test.com", 30),
              new TestClass.User("Bob", "bob@test.com", 25),
              new TestClass.User("Charlie", "charlie@test.com", 35));
      Object[] args = new Object[] {users};

      String result = SpELKeyResolver.resolve("#{#users.size()}", method, args);

      assertEquals("3", result);
    }

    @Test
    @DisplayName("Should resolve collection indexing #{#users[0].name}")
    void shouldResolveCollectionIndexing() throws Exception {
      Method method = TestClass.class.getMethod("processFirstUser", List.class);
      List<TestClass.User> users =
          Arrays.asList(new TestClass.User("FirstUser", "first@test.com", 28));
      Object[] args = new Object[] {users};

      String result = SpELKeyResolver.resolve("#{#users[0].name}", method, args);

      assertEquals("FirstUser", result);
    }

    @Test
    @DisplayName("Should resolve conditional expression #{#id > 100 ? 'large' : 'small'}")
    void shouldResolveConditionalExpression() throws Exception {
      Method method = TestClass.class.getMethod("processConditional", int.class);

      // Test with large value
      String result1 =
          SpELKeyResolver.resolve("#{#id > 100 ? 'large' : 'small'}", method, new Object[] {150});
      assertEquals("large", result1);

      // Test with small value
      String result2 =
          SpELKeyResolver.resolve("#{#id > 100 ? 'large' : 'small'}", method, new Object[] {50});
      assertEquals("small", result2);
    }

    @Test
    @DisplayName("Should throw exception when SpEL evaluates to null")
    void shouldThrowWhenEvaluatesToNull() throws Exception {
      Method method = TestClass.class.getMethod("processUser", String.class);
      Object[] args = new Object[] {null};

      IllegalArgumentException exception =
          assertThrows(
              IllegalArgumentException.class,
              () -> SpELKeyResolver.resolve("#{#userId}", method, args));

      assertTrue(exception.getMessage().contains("evaluated to null"));
    }

    @Test
    @DisplayName("Should throw exception when SpEL evaluates to blank string")
    void shouldThrowWhenEvaluatesToBlank() throws Exception {
      Method method = TestClass.class.getMethod("processUser", String.class);
      Object[] args = new Object[] {"   "};

      IllegalArgumentException exception =
          assertThrows(
              IllegalArgumentException.class,
              () -> SpELKeyResolver.resolve("#{#userId}", method, args));

      assertTrue(exception.getMessage().contains("evaluated to blank"));
    }

    @Test
    @DisplayName("Should throw exception when SpEL evaluates to empty string")
    void shouldThrowWhenEvaluatesToEmpty() throws Exception {
      Method method = TestClass.class.getMethod("processUser", String.class);
      Object[] args = new Object[] {""};

      IllegalArgumentException exception =
          assertThrows(
              IllegalArgumentException.class,
              () -> SpELKeyResolver.resolve("#{#userId}", method, args));

      assertTrue(exception.getMessage().contains("evaluated to blank"));
    }
  }

  @Nested
  @DisplayName("Legacy Syntax Tests (No Longer Supported - Treated as Literals)")
  class LegacySyntaxTests {

    @Test
    @DisplayName("Should treat #userId as literal (not SpEL)")
    void shouldTreatLegacyAsLiteral() throws Exception {
      Method method = TestClass.class.getMethod("processUser", String.class);
      Object[] args = new Object[] {"user789"};

      // Without #{} wrapper, it's treated as literal
      String result = SpELKeyResolver.resolve("#userId", method, args);

      assertEquals("#userId", result); // Returns literal, not evaluated
    }

    @Test
    @DisplayName("Should treat 'user-' + #id as literal (not SpEL)")
    void shouldTreatLegacyConcatenationAsLiteral() throws Exception {
      Method method = TestClass.class.getMethod("processWithPrefix", Long.class);
      Object[] args = new Object[] {333L};

      String result = SpELKeyResolver.resolve("'user-' + #id", method, args);

      assertEquals("'user-' + #id", result); // Returns literal
    }

    @Test
    @DisplayName("Should treat #user.name as literal (not SpEL)")
    void shouldTreatLegacyObjectPropertyAsLiteral() throws Exception {
      Method method = TestClass.class.getMethod("updateUser", TestClass.User.class);
      Object[] args = new Object[] {new TestClass.User("David", "david@test.com", 40)};

      String result = SpELKeyResolver.resolve("#user.name", method, args);

      assertEquals("#user.name", result); // Returns literal
    }

    @Test
    @DisplayName("Should treat #p0 as literal (not SpEL)")
    void shouldTreatLegacyP0AsLiteral() throws Exception {
      Method method = TestClass.class.getMethod("processWithP0", String.class);
      Object[] args = new Object[] {"legacy-p0"};

      String result = SpELKeyResolver.resolve("#p0", method, args);

      assertEquals("#p0", result); // Returns literal
    }
  }

  @Nested
  @DisplayName("Literal Key Tests (Issue #13 Fixed)")
  class LiteralKeyTests {

    @Test
    @DisplayName("Should treat 'order#123' as literal key")
    void shouldTreatOrderHashAsLiteral() throws Exception {
      Method method = TestClass.class.getMethod("processLiteralWithHash");
      Object[] args = new Object[] {};

      String result = SpELKeyResolver.resolve("order#123", method, args);

      assertEquals("order#123", result);
    }

    @Test
    @DisplayName("Should treat 'item-#1' as literal key")
    void shouldTreatItemHashAsLiteral() throws Exception {
      Method method = TestClass.class.getMethod("processLiteralWithHashPrefix");
      Object[] args = new Object[] {};

      String result = SpELKeyResolver.resolve("item-#1", method, args);

      assertEquals("item-#1", result);
    }

    @Test
    @DisplayName("Should treat 'task#end' as literal key")
    void shouldTreatTaskHashAsLiteral() throws Exception {
      Method method = TestClass.class.getMethod("processLiteralWithHashSuffix");
      Object[] args = new Object[] {};

      String result = SpELKeyResolver.resolve("task#end", method, args);

      assertEquals("task#end", result);
    }

    @Test
    @DisplayName("Should treat 'prefix#middle#suffix' as literal key")
    void shouldTreatMultipleHashesAsLiteral() throws Exception {
      Method method = TestClass.class.getMethod("processMultipleHashes");
      Object[] args = new Object[] {};

      String result = SpELKeyResolver.resolve("prefix#middle#suffix", method, args);

      assertEquals("prefix#middle#suffix", result);
    }

    @Test
    @DisplayName("Should treat keys without # as literal")
    void shouldTreatNoHashAsLiteral() throws Exception {
      Method method = TestClass.class.getMethod("processLiteralNoHash");
      Object[] args = new Object[] {};

      String result = SpELKeyResolver.resolve("valid:key:123", method, args);

      assertEquals("valid:key:123", result);
    }

    @Test
    @DisplayName("Should handle single # as literal (not SpEL)")
    void shouldHandleSingleHashAsLiteral() throws Exception {
      Method method = TestClass.class.getMethod("processLiteralNoHash");
      Object[] args = new Object[] {};

      String result = SpELKeyResolver.resolve("#", method, args);

      assertEquals("#", result);
    }

    @Test
    @DisplayName("Should handle ## as literal")
    void shouldHandleDoubleHashAsLiteral() throws Exception {
      Method method = TestClass.class.getMethod("processLiteralNoHash");
      Object[] args = new Object[] {};

      String result = SpELKeyResolver.resolve("##", method, args);

      assertEquals("##", result);
    }

    @Test
    @DisplayName("Should handle special characters in literal keys")
    void shouldHandleSpecialCharacters() throws Exception {
      Method method = TestClass.class.getMethod("processLiteralNoHash");
      Object[] args = new Object[] {};

      String result =
          SpELKeyResolver.resolve("key:with-special_chars.and#hash/slash", method, args);

      assertEquals("key:with-special_chars.and#hash/slash", result);
    }

    @Test
    @DisplayName("Should handle Unicode in literal keys")
    void shouldHandleUnicode() throws Exception {
      Method method = TestClass.class.getMethod("processLiteralNoHash");
      Object[] args = new Object[] {};

      String result = SpELKeyResolver.resolve("用户#123#café", method, args);

      assertEquals("用户#123#café", result);
    }

    @Test
    @DisplayName("Should handle empty string as literal")
    void shouldHandleEmptyString() throws Exception {
      Method method = TestClass.class.getMethod("processLiteralNoHash");
      Object[] args = new Object[] {};

      String result = SpELKeyResolver.resolve("", method, args);

      assertEquals("", result);
    }
  }

  @Nested
  @DisplayName("Edge Cases and Error Handling")
  class EdgeCaseTests {

    @Test
    @DisplayName("Should handle whitespace in SpEL expressions")
    void shouldHandleWhitespaceInSpel() throws Exception {
      Method method = TestClass.class.getMethod("processUser", String.class);
      Object[] args = new Object[] {"user999"};

      String result = SpELKeyResolver.resolve("#{  #userId  }", method, args);

      assertEquals("user999", result);
    }

    @Test
    @DisplayName("Should throw exception for malformed SpEL")
    void shouldThrowForMalformedSpel() throws Exception {
      Method method = TestClass.class.getMethod("processUser", String.class);
      Object[] args = new Object[] {"user123"};

      assertThrows(Exception.class, () -> SpELKeyResolver.resolve("#{#userId +}", method, args));
    }

    @Test
    @DisplayName("Should throw exception for undefined variable")
    void shouldThrowForUndefinedVariable() throws Exception {
      Method method = TestClass.class.getMethod("processUser", String.class);
      Object[] args = new Object[] {"user123"};

      assertThrows(
          Exception.class, () -> SpELKeyResolver.resolve("#{#undefinedVariable}", method, args));
    }

    @Test
    @DisplayName("Should handle empty SpEL expression #{}")
    void shouldHandleEmptySpel() throws Exception {
      Method method = TestClass.class.getMethod("processUser", String.class);
      Object[] args = new Object[] {"user123"};

      assertThrows(Exception.class, () -> SpELKeyResolver.resolve("#{}", method, args));
    }

    @Test
    @DisplayName("Should handle nested braces in SpEL")
    void shouldHandleNestedBraces() throws Exception {
      Method method = TestClass.class.getMethod("processConditional", int.class);
      Object[] args = new Object[] {50};

      // Complex nested expression
      String result = SpELKeyResolver.resolve("#{T(java.lang.Math).max(#id, 100)}", method, args);

      assertEquals("100", result);
    }
  }

  @Nested
  @DisplayName("Syntax Detection Tests")
  class SyntaxDetectionTests {

    @Test
    @DisplayName("Should detect #{} syntax and evaluate as SpEL")
    void shouldDetectSpelSyntax() throws Exception {
      Method method = TestClass.class.getMethod("processUser", String.class);
      Object[] args = new Object[] {"test"};

      // These use #{} syntax and should be evaluated as SpEL
      assertEquals("test", SpELKeyResolver.resolve("#{#userId}", method, args));
      assertEquals("test", SpELKeyResolver.resolve("#{ #userId }", method, args));
    }

    @Test
    @DisplayName("Should NOT detect SpEL without #{} wrapper")
    void shouldNotDetectSpelWithoutWrapper() throws Exception {
      Method method = TestClass.class.getMethod("processUser", String.class);
      Object[] args = new Object[] {"test"};

      // Without #{} wrapper, these are treated as literals
      assertEquals("#userId", SpELKeyResolver.resolve("#userId", method, args));
      assertEquals("#p0", SpELKeyResolver.resolve("#p0", method, args));
      assertEquals("'user-' + #id", SpELKeyResolver.resolve("'user-' + #id", method, args));
    }

    @Test
    @DisplayName("Should NOT detect SpEL in malformed expressions with #{}")
    void shouldNotDetectMalformedBrace() throws Exception {
      Method method = TestClass.class.getMethod("processConditional", int.class);
      Object[] args = new Object[] {42};

      // This contains #{} but doesn't start/end with it, so it's treated as literal
      String result = SpELKeyResolver.resolve("T(String).valueOf(#{#id})", method, args);
      assertEquals("T(String).valueOf(#{#id})", result);
    }

    @Test
    @DisplayName("Should NOT detect SpEL in pure literal keys")
    void shouldNotDetectSpelInLiterals() throws Exception {
      Method method = TestClass.class.getMethod("processLiteralNoHash");
      Object[] args = new Object[] {};

      // These should be treated as literals (no SpEL detection)
      assertEquals("order#123", SpELKeyResolver.resolve("order#123", method, args));
      assertEquals("item-#1", SpELKeyResolver.resolve("item-#1", method, args));
      assertEquals("task#", SpELKeyResolver.resolve("task#", method, args));
      assertEquals("#", SpELKeyResolver.resolve("#", method, args));
      assertEquals("##", SpELKeyResolver.resolve("##", method, args));
      assertEquals("#userId", SpELKeyResolver.resolve("#userId", method, args));
      assertEquals("#user.name", SpELKeyResolver.resolve("#user.name", method, args));
    }

    @Test
    @DisplayName("Should correctly identify SpEL expressions with isSpELExpression")
    void shouldIdentifySpELExpressions() {
      assertTrue(SpELKeyResolver.isSpELExpression("#{#userId}"));
      assertTrue(SpELKeyResolver.isSpELExpression("#{ #userId }"));
      assertTrue(SpELKeyResolver.isSpELExpression("#{T(String).valueOf(#id)}"));

      assertFalse(SpELKeyResolver.isSpELExpression("#userId"));
      assertFalse(SpELKeyResolver.isSpELExpression("order#123"));
      assertFalse(SpELKeyResolver.isSpELExpression("literal-key"));
      assertFalse(SpELKeyResolver.isSpELExpression(""));
      assertFalse(SpELKeyResolver.isSpELExpression("#"));
      assertFalse(SpELKeyResolver.isSpELExpression("T(String).valueOf(#{#id})"));
    }
  }
}
