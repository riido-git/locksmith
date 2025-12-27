package in.riido.locksmith.aspect;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import in.riido.locksmith.DistributedLock;
import in.riido.locksmith.autoconfigure.LocksmithProperties;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;

/**
 * Comprehensive tests for SpEL key resolution in DistributedLockAspect.
 *
 * <p>Tests both the new recommended #{...} syntax and legacy syntax (with deprecation warnings).
 *
 * @see <a href="https://github.com/riido-git/locksmith/issues/13">GitHub Issue #13</a>
 */
@DisplayName("SpEL Key Resolution Tests")
class SpELKeyResolutionTest {

  private DistributedLockAspect aspect;
  private ProceedingJoinPoint joinPoint;
  private MethodSignature methodSignature;

  @BeforeEach
  void setUp() {
    RedissonClient redissonClient = mock(RedissonClient.class);
    LocksmithProperties lockProperties =
        new LocksmithProperties(Duration.ofMinutes(10), Duration.ofSeconds(60), "lock:", false);
    aspect = new DistributedLockAspect(redissonClient, lockProperties);
    joinPoint = mock(ProceedingJoinPoint.class);
    methodSignature = mock(MethodSignature.class);

    when(joinPoint.getSignature()).thenReturn(methodSignature);
    when(methodSignature.getDeclaringType()).thenReturn(TestClass.class);
    when(methodSignature.getName()).thenReturn("testMethod");
  }

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
      when(methodSignature.getMethod()).thenReturn(method);
      when(joinPoint.getArgs()).thenReturn(new Object[] {"user123"});

      String result = aspect.resolveKey("#{#userId}", method, joinPoint);

      assertEquals("user123", result);
    }

    @Test
    @DisplayName("Should resolve Long parameter #{#orderId}")
    void shouldResolveLongParameter() throws Exception {
      Method method = TestClass.class.getMethod("processOrder", Long.class);
      when(methodSignature.getMethod()).thenReturn(method);
      when(joinPoint.getArgs()).thenReturn(new Object[] {42L});

      String result = aspect.resolveKey("#{#orderId}", method, joinPoint);

      assertEquals("42", result);
    }

    @Test
    @DisplayName("Should resolve string concatenation #{'user-' + #id}")
    void shouldResolveStringConcatenation() throws Exception {
      Method method = TestClass.class.getMethod("processWithPrefix", Long.class);
      when(methodSignature.getMethod()).thenReturn(method);
      when(joinPoint.getArgs()).thenReturn(new Object[] {99L});

      String result = aspect.resolveKey("#{'user-' + #id}", method, joinPoint);

      assertEquals("user-99", result);
    }

    @Test
    @DisplayName("Should resolve object property #{#user.name}")
    void shouldResolveObjectProperty() throws Exception {
      Method method = TestClass.class.getMethod("updateUser", TestClass.User.class);
      when(methodSignature.getMethod()).thenReturn(method);
      when(joinPoint.getArgs())
          .thenReturn(new Object[] {new TestClass.User("Alice", "alice@example.com", 30)});

      String result = aspect.resolveKey("#{#user.name}", method, joinPoint);

      assertEquals("Alice", result);
    }

    @Test
    @DisplayName("Should resolve nested property #{#user.email}")
    void shouldResolveNestedProperty() throws Exception {
      Method method = TestClass.class.getMethod("processUserEmail", TestClass.User.class);
      when(methodSignature.getMethod()).thenReturn(method);
      when(joinPoint.getArgs())
          .thenReturn(new Object[] {new TestClass.User("Bob", "bob@test.com", 25)});

      String result = aspect.resolveKey("#{#user.email}", method, joinPoint);

      assertEquals("bob@test.com", result);
    }

    @Test
    @DisplayName("Should resolve parameter by position #{#p0}")
    void shouldResolveByPositionP0() throws Exception {
      Method method = TestClass.class.getMethod("processWithP0", String.class);
      when(methodSignature.getMethod()).thenReturn(method);
      when(joinPoint.getArgs()).thenReturn(new Object[] {"value-p0"});

      String result = aspect.resolveKey("#{#p0}", method, joinPoint);

      assertEquals("value-p0", result);
    }

    @Test
    @DisplayName("Should resolve parameter by position #{#a0}")
    void shouldResolveByPositionA0() throws Exception {
      Method method = TestClass.class.getMethod("processWithA0", String.class);
      when(methodSignature.getMethod()).thenReturn(method);
      when(joinPoint.getArgs()).thenReturn(new Object[] {"value-a0"});

      String result = aspect.resolveKey("#{#a0}", method, joinPoint);

      assertEquals("value-a0", result);
    }

    @Test
    @DisplayName("Should resolve multiple parameters #{#userId + '-' + #role}")
    void shouldResolveMultipleParameters() throws Exception {
      Method method = TestClass.class.getMethod("processUserRole", String.class, String.class);
      when(methodSignature.getMethod()).thenReturn(method);
      when(joinPoint.getArgs()).thenReturn(new Object[] {"user456", "admin"});

      String result = aspect.resolveKey("#{#userId + '-' + #role}", method, joinPoint);

      assertEquals("user456-admin", result);
    }

    @Test
    @DisplayName("Should resolve static method call #{T(String).valueOf(#id)}")
    void shouldResolveStaticMethodCall() throws Exception {
      Method method = TestClass.class.getMethod("processWithStaticMethod", Integer.class);
      when(methodSignature.getMethod()).thenReturn(method);
      when(joinPoint.getArgs()).thenReturn(new Object[] {789});

      String result = aspect.resolveKey("#{T(java.lang.String).valueOf(#id)}", method, joinPoint);

      assertEquals("789", result);
    }

    @Test
    @DisplayName("Should resolve collection method #{#users.size()}")
    void shouldResolveCollectionMethod() throws Exception {
      Method method = TestClass.class.getMethod("processUsersList", List.class);
      when(methodSignature.getMethod()).thenReturn(method);
      List<TestClass.User> users =
          Arrays.asList(
              new TestClass.User("Alice", "alice@test.com", 30),
              new TestClass.User("Bob", "bob@test.com", 25),
              new TestClass.User("Charlie", "charlie@test.com", 35));
      when(joinPoint.getArgs()).thenReturn(new Object[] {users});

      String result = aspect.resolveKey("#{#users.size()}", method, joinPoint);

      assertEquals("3", result);
    }

    @Test
    @DisplayName("Should resolve collection indexing #{#users[0].name}")
    void shouldResolveCollectionIndexing() throws Exception {
      Method method = TestClass.class.getMethod("processFirstUser", List.class);
      when(methodSignature.getMethod()).thenReturn(method);
      List<TestClass.User> users =
          Arrays.asList(new TestClass.User("FirstUser", "first@test.com", 28));
      when(joinPoint.getArgs()).thenReturn(new Object[] {users});

      String result = aspect.resolveKey("#{#users[0].name}", method, joinPoint);

      assertEquals("FirstUser", result);
    }

    @Test
    @DisplayName("Should resolve conditional expression #{#id > 100 ? 'large' : 'small'}")
    void shouldResolveConditionalExpression() throws Exception {
      Method method = TestClass.class.getMethod("processConditional", int.class);
      when(methodSignature.getMethod()).thenReturn(method);

      // Test with large value
      when(joinPoint.getArgs()).thenReturn(new Object[] {150});
      String result1 = aspect.resolveKey("#{#id > 100 ? 'large' : 'small'}", method, joinPoint);
      assertEquals("large", result1);

      // Test with small value
      when(joinPoint.getArgs()).thenReturn(new Object[] {50});
      String result2 = aspect.resolveKey("#{#id > 100 ? 'large' : 'small'}", method, joinPoint);
      assertEquals("small", result2);
    }

    @Test
    @DisplayName("Should throw exception when SpEL evaluates to null")
    void shouldThrowWhenEvaluatesToNull() throws Exception {
      Method method = TestClass.class.getMethod("processUser", String.class);
      when(methodSignature.getMethod()).thenReturn(method);
      when(joinPoint.getArgs()).thenReturn(new Object[] {null});

      IllegalArgumentException exception =
          assertThrows(
              IllegalArgumentException.class,
              () -> aspect.resolveKey("#{#userId}", method, joinPoint));

      assertTrue(exception.getMessage().contains("evaluated to null"));
    }

    @Test
    @DisplayName("Should throw exception when SpEL evaluates to blank string")
    void shouldThrowWhenEvaluatesToBlank() throws Exception {
      Method method = TestClass.class.getMethod("processUser", String.class);
      when(methodSignature.getMethod()).thenReturn(method);
      when(joinPoint.getArgs()).thenReturn(new Object[] {"   "});

      IllegalArgumentException exception =
          assertThrows(
              IllegalArgumentException.class,
              () -> aspect.resolveKey("#{#userId}", method, joinPoint));

      assertTrue(exception.getMessage().contains("evaluated to blank"));
    }

    @Test
    @DisplayName("Should throw exception when SpEL evaluates to empty string")
    void shouldThrowWhenEvaluatesToEmpty() throws Exception {
      Method method = TestClass.class.getMethod("processUser", String.class);
      when(methodSignature.getMethod()).thenReturn(method);
      when(joinPoint.getArgs()).thenReturn(new Object[] {""});

      IllegalArgumentException exception =
          assertThrows(
              IllegalArgumentException.class,
              () -> aspect.resolveKey("#{#userId}", method, joinPoint));

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
      when(methodSignature.getMethod()).thenReturn(method);
      when(joinPoint.getArgs()).thenReturn(new Object[] {"user789"});

      // Without #{} wrapper, it's treated as literal
      String result = aspect.resolveKey("#userId", method, joinPoint);

      assertEquals("#userId", result); // Returns literal, not evaluated
    }

    @Test
    @DisplayName("Should treat 'user-' + #id as literal (not SpEL)")
    void shouldTreatLegacyConcatenationAsLiteral() throws Exception {
      Method method = TestClass.class.getMethod("processWithPrefix", Long.class);
      when(methodSignature.getMethod()).thenReturn(method);
      when(joinPoint.getArgs()).thenReturn(new Object[] {333L});

      String result = aspect.resolveKey("'user-' + #id", method, joinPoint);

      assertEquals("'user-' + #id", result); // Returns literal
    }

    @Test
    @DisplayName("Should treat #user.name as literal (not SpEL)")
    void shouldTreatLegacyObjectPropertyAsLiteral() throws Exception {
      Method method = TestClass.class.getMethod("updateUser", TestClass.User.class);
      when(methodSignature.getMethod()).thenReturn(method);
      when(joinPoint.getArgs())
          .thenReturn(new Object[] {new TestClass.User("David", "david@test.com", 40)});

      String result = aspect.resolveKey("#user.name", method, joinPoint);

      assertEquals("#user.name", result); // Returns literal
    }

    @Test
    @DisplayName("Should treat #p0 as literal (not SpEL)")
    void shouldTreatLegacyP0AsLiteral() throws Exception {
      Method method = TestClass.class.getMethod("processWithP0", String.class);
      when(methodSignature.getMethod()).thenReturn(method);
      when(joinPoint.getArgs()).thenReturn(new Object[] {"legacy-p0"});

      String result = aspect.resolveKey("#p0", method, joinPoint);

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
      when(methodSignature.getMethod()).thenReturn(method);
      when(joinPoint.getArgs()).thenReturn(new Object[] {});

      String result = aspect.resolveKey("order#123", method, joinPoint);

      assertEquals("order#123", result);
    }

    @Test
    @DisplayName("Should treat 'item-#1' as literal key")
    void shouldTreatItemHashAsLiteral() throws Exception {
      Method method = TestClass.class.getMethod("processLiteralWithHashPrefix");
      when(methodSignature.getMethod()).thenReturn(method);
      when(joinPoint.getArgs()).thenReturn(new Object[] {});

      String result = aspect.resolveKey("item-#1", method, joinPoint);

      assertEquals("item-#1", result);
    }

    @Test
    @DisplayName("Should treat 'task#end' as literal key")
    void shouldTreatTaskHashAsLiteral() throws Exception {
      Method method = TestClass.class.getMethod("processLiteralWithHashSuffix");
      when(methodSignature.getMethod()).thenReturn(method);
      when(joinPoint.getArgs()).thenReturn(new Object[] {});

      String result = aspect.resolveKey("task#end", method, joinPoint);

      assertEquals("task#end", result);
    }

    @Test
    @DisplayName("Should treat 'prefix#middle#suffix' as literal key")
    void shouldTreatMultipleHashesAsLiteral() throws Exception {
      Method method = TestClass.class.getMethod("processMultipleHashes");
      when(methodSignature.getMethod()).thenReturn(method);
      when(joinPoint.getArgs()).thenReturn(new Object[] {});

      String result = aspect.resolveKey("prefix#middle#suffix", method, joinPoint);

      assertEquals("prefix#middle#suffix", result);
    }

    @Test
    @DisplayName("Should treat keys without # as literal")
    void shouldTreatNoHashAsLiteral() throws Exception {
      Method method = TestClass.class.getMethod("processLiteralNoHash");
      when(methodSignature.getMethod()).thenReturn(method);
      when(joinPoint.getArgs()).thenReturn(new Object[] {});

      String result = aspect.resolveKey("valid:key:123", method, joinPoint);

      assertEquals("valid:key:123", result);
    }

    @Test
    @DisplayName("Should handle single # as literal (not SpEL)")
    void shouldHandleSingleHashAsLiteral() throws Exception {
      Method method = TestClass.class.getMethod("processLiteralNoHash");
      when(methodSignature.getMethod()).thenReturn(method);
      when(joinPoint.getArgs()).thenReturn(new Object[] {});

      String result = aspect.resolveKey("#", method, joinPoint);

      assertEquals("#", result);
    }

    @Test
    @DisplayName("Should handle ## as literal")
    void shouldHandleDoubleHashAsLiteral() throws Exception {
      Method method = TestClass.class.getMethod("processLiteralNoHash");
      when(methodSignature.getMethod()).thenReturn(method);
      when(joinPoint.getArgs()).thenReturn(new Object[] {});

      String result = aspect.resolveKey("##", method, joinPoint);

      assertEquals("##", result);
    }

    @Test
    @DisplayName("Should handle special characters in literal keys")
    void shouldHandleSpecialCharacters() throws Exception {
      Method method = TestClass.class.getMethod("processLiteralNoHash");
      when(methodSignature.getMethod()).thenReturn(method);
      when(joinPoint.getArgs()).thenReturn(new Object[] {});

      String result = aspect.resolveKey("key:with-special_chars.and#hash/slash", method, joinPoint);

      assertEquals("key:with-special_chars.and#hash/slash", result);
    }

    @Test
    @DisplayName("Should handle Unicode in literal keys")
    void shouldHandleUnicode() throws Exception {
      Method method = TestClass.class.getMethod("processLiteralNoHash");
      when(methodSignature.getMethod()).thenReturn(method);
      when(joinPoint.getArgs()).thenReturn(new Object[] {});

      String result = aspect.resolveKey("用户#123#café", method, joinPoint);

      assertEquals("用户#123#café", result);
    }

    @Test
    @DisplayName("Should handle empty string as literal")
    void shouldHandleEmptyString() throws Exception {
      Method method = TestClass.class.getMethod("processLiteralNoHash");
      when(methodSignature.getMethod()).thenReturn(method);
      when(joinPoint.getArgs()).thenReturn(new Object[] {});

      String result = aspect.resolveKey("", method, joinPoint);

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
      when(methodSignature.getMethod()).thenReturn(method);
      when(joinPoint.getArgs()).thenReturn(new Object[] {"user999"});

      String result = aspect.resolveKey("#{  #userId  }", method, joinPoint);

      assertEquals("user999", result);
    }

    @Test
    @DisplayName("Should throw exception for malformed SpEL")
    void shouldThrowForMalformedSpel() throws Exception {
      Method method = TestClass.class.getMethod("processUser", String.class);
      when(methodSignature.getMethod()).thenReturn(method);
      when(joinPoint.getArgs()).thenReturn(new Object[] {"user123"});

      assertThrows(
          Exception.class,
          () -> {
            aspect.resolveKey("#{#userId +}", method, joinPoint);
          });
    }

    @Test
    @DisplayName("Should throw exception for undefined variable")
    void shouldThrowForUndefinedVariable() throws Exception {
      Method method = TestClass.class.getMethod("processUser", String.class);
      when(methodSignature.getMethod()).thenReturn(method);
      when(joinPoint.getArgs()).thenReturn(new Object[] {"user123"});

      assertThrows(
          Exception.class,
          () -> {
            aspect.resolveKey("#{#undefinedVariable}", method, joinPoint);
          });
    }

    @Test
    @DisplayName("Should handle empty SpEL expression #{}")
    void shouldHandleEmptySpel() throws Exception {
      Method method = TestClass.class.getMethod("processUser", String.class);
      when(methodSignature.getMethod()).thenReturn(method);
      when(joinPoint.getArgs()).thenReturn(new Object[] {"user123"});

      assertThrows(
          Exception.class,
          () -> {
            aspect.resolveKey("#{}", method, joinPoint);
          });
    }

    @Test
    @DisplayName("Should handle nested braces in SpEL")
    void shouldHandleNestedBraces() throws Exception {
      Method method = TestClass.class.getMethod("processConditional", int.class);
      when(methodSignature.getMethod()).thenReturn(method);
      when(joinPoint.getArgs()).thenReturn(new Object[] {50});

      // Complex nested expression
      String result = aspect.resolveKey("#{T(java.lang.Math).max(#id, 100)}", method, joinPoint);

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
      when(methodSignature.getMethod()).thenReturn(method);
      when(joinPoint.getArgs()).thenReturn(new Object[] {"test"});

      // These use #{} syntax and should be evaluated as SpEL
      assertEquals("test", aspect.resolveKey("#{#userId}", method, joinPoint));
      assertEquals("test", aspect.resolveKey("#{ #userId }", method, joinPoint));
    }

    @Test
    @DisplayName("Should NOT detect SpEL without #{} wrapper")
    void shouldNotDetectSpelWithoutWrapper() throws Exception {
      Method method = TestClass.class.getMethod("processUser", String.class);
      when(methodSignature.getMethod()).thenReturn(method);
      when(joinPoint.getArgs()).thenReturn(new Object[] {"test"});

      // Without #{} wrapper, these are treated as literals
      assertEquals("#userId", aspect.resolveKey("#userId", method, joinPoint));
      assertEquals("#p0", aspect.resolveKey("#p0", method, joinPoint));
      assertEquals("'user-' + #id", aspect.resolveKey("'user-' + #id", method, joinPoint));
    }

    @Test
    @DisplayName("Should NOT detect SpEL in malformed expressions with #{}")
    void shouldNotDetectMalformedBrace() throws Exception {
      Method method = TestClass.class.getMethod("processConditional", int.class);
      when(methodSignature.getMethod()).thenReturn(method);
      when(joinPoint.getArgs()).thenReturn(new Object[] {42});

      // This contains #{} but doesn't start/end with it, so it's treated as literal
      // To use this as SpEL, it should be: #{T(String).valueOf(#id)}
      String result = aspect.resolveKey("T(String).valueOf(#{#id})", method, joinPoint);
      assertEquals("T(String).valueOf(#{#id})", result);
    }

    @Test
    @DisplayName("Should NOT detect SpEL in pure literal keys")
    void shouldNotDetectSpelInLiterals() throws Exception {
      Method method = TestClass.class.getMethod("processLiteralNoHash");
      when(methodSignature.getMethod()).thenReturn(method);
      when(joinPoint.getArgs()).thenReturn(new Object[] {});

      // These should be treated as literals (no SpEL detection)
      assertEquals("order#123", aspect.resolveKey("order#123", method, joinPoint));
      assertEquals("item-#1", aspect.resolveKey("item-#1", method, joinPoint));
      assertEquals("task#", aspect.resolveKey("task#", method, joinPoint));
      assertEquals("#", aspect.resolveKey("#", method, joinPoint));
      assertEquals("##", aspect.resolveKey("##", method, joinPoint));
      assertEquals("#userId", aspect.resolveKey("#userId", method, joinPoint));
      assertEquals("#user.name", aspect.resolveKey("#user.name", method, joinPoint));
    }
  }
}
