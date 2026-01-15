package in.riido.locksmith.aspect;

import static org.junit.jupiter.api.Assertions.*;

import in.riido.locksmith.DistributedLock;
import in.riido.locksmith.support.SpELKeyResolver;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Test cases for all SpEL examples from the GitHub Wiki.
 *
 * @see <a
 *     href="https://github.com/riido-git/locksmith/wiki/Dynamic-Keys-with-SpEL">Dynamic-Keys-with-SpEL
 *     Wiki</a>
 */
@DisplayName("Wiki SpEL Examples Tests")
class WikiSpELExamplesTest {

  /** Test class with all wiki examples. */
  public static class WikiExamples {

    // Basic Parameter Reference
    @DistributedLock(key = "#{#userId}")
    public void updateUser(String userId) {}

    @DistributedLock(key = "#{#orderId}")
    public void processOrder(Long orderId) {}

    // String Concatenation
    @DistributedLock(key = "#{'user-' + #userId}")
    public void updateUserWithPrefix(String userId) {}

    @DistributedLock(key = "#{'order-' + #orderId + '-process'}")
    public void processOrderWithSuffix(Long orderId) {}

    // Object Properties
    @DistributedLock(key = "#{#user.id}")
    public void updateUserById(User user) {}

    @DistributedLock(key = "#{#order.customer.id}")
    public void processOrderByCustomerId(Order order) {}

    @DistributedLock(key = "#{'user-' + #user.email}")
    public void sendEmail(User user) {}

    // Multiple Parameters
    @DistributedLock(key = "#{#tenantId + '-' + #userId}")
    public void updateUserInTenant(String tenantId, String userId) {}

    @DistributedLock(key = "#{'transfer-' + #fromAccount + '-to-' + #toAccount}")
    public void transfer(String fromAccount, String toAccount, BigDecimal amount) {}

    // Parameter by Position
    @DistributedLock(key = "#{#p0}")
    public void processByP0(String userId) {}

    @DistributedLock(key = "#{#a0 + '-' + #a1}")
    public void processByPosition(String first, String second) {}

    // Method Calls
    @DistributedLock(key = "#{#email.toLowerCase()}")
    public void processEmail(String email) {}

    @DistributedLock(key = "#{#list.size()}")
    public void processList(List<String> list) {}

    @DistributedLock(key = "#{#date.toLocalDate().toString()}")
    public void processForDate(LocalDateTime date) {}

    @DistributedLock(key = "#{#user.getId()}")
    public void processUserGetId(User user) {}

    // Conditional Expressions
    @DistributedLock(key = "#{#premium ? 'premium-' + #userId : 'standard-' + #userId}")
    public void processUserPremium(String userId, boolean premium) {}

    @DistributedLock(key = "#{#amount > 1000 ? 'large' : 'small'}")
    public void processPayment(double amount) {}

    // Null Safety - Elvis operator
    @DistributedLock(key = "#{#user.nickname ?: #user.id}")
    public void updateUserWithElvis(User user) {}

    @DistributedLock(key = "#{#order?.customer?.id ?: 'anonymous'}")
    public void processOrderSafeNavigation(Order order) {}

    // Array and Collection Access
    @DistributedLock(key = "#{#ids[0]}")
    public void processFirst(List<String> ids) {}

    @DistributedLock(key = "#{#args[0] + '-' + #args[1]}")
    public void processVarargs(String... args) {}

    @DistributedLock(key = "#{#users[0].name}")
    public void processFirstUser(List<User> users) {}

    // Map Access
    @DistributedLock(key = "#{#params['orderId']}")
    public void processMap(Map<String, String> params) {}

    @DistributedLock(key = "#{#context['tenant'] + '-' + #context['user']}")
    public void processInContext(Map<String, Object> context) {}

    // Static Methods
    @DistributedLock(key = "#{T(java.lang.String).valueOf(#orderId)}")
    public void processWithStaticMethod(Long orderId) {}

    @DistributedLock(key = "#{T(java.lang.Math).max(#a, #b)}")
    public void processMax(int a, int b) {}

    // Common Patterns - Per-User Locking
    @DistributedLock(key = "#{'user-profile-' + #userId}")
    public void updateProfile(String userId, Profile profile) {}

    @DistributedLock(key = "#{'user-settings-' + #user.id}")
    public void updateSettings(User user, Settings settings) {}

    // Common Patterns - Per-Resource Locking
    @DistributedLock(key = "#{'document-' + #documentId}")
    public void editDocument(String documentId, String content) {}

    @DistributedLock(key = "#{'file-' + #path.hashCode()}")
    public void writeFile(Path path, byte[] data) {}

    // Common Patterns - Per-Tenant Locking
    @DistributedLock(key = "#{#tenantId + ':user:' + #userId}")
    public void updateTenantUserColon(String tenantId, String userId) {}

    // Common Patterns - Composite Keys
    @DistributedLock(key = "#{'inventory-' + #warehouseId + '-' + #productId}")
    public void updateInventory(String warehouseId, String productId, int quantity) {}

    // Common Patterns - Date-Based Locking
    @DistributedLock(
        key = "#{'hourly-' + #instant.truncatedTo(T(java.time.temporal.ChronoUnit).HOURS)}")
    public void hourlyTask(Instant instant) {}

    // Literal Keys with # Character
    @DistributedLock(key = "order#123")
    public void processOrderLiteral() {}

    @DistributedLock(key = "task#end")
    public void endTask() {}

    @DistributedLock(key = "item-#1")
    public void processItem() {}

    @DistributedLock(key = "prefix#middle#suffix")
    public void processMultipleHashes() {}

    // Best Practices - Normalize Keys
    @DistributedLock(key = "#{'email-' + #email.toLowerCase().trim()}")
    public void processEmailNormalized(String email) {}

    // Best Practices - Use Prefixes
    @DistributedLock(key = "#{'read:' + #docId}")
    public void readDocument(String docId) {}

    @DistributedLock(key = "#{'write:' + #docId}")
    public void writeDocument(String docId, Document doc) {}

    // Test domain objects
    public record User(String id, String name, String email, String nickname) {
      public User(String id, String name, String email) {
        this(id, name, email, null);
      }

      public String getId() {
        return id;
      }
    }

    public record Customer(String id, String name) {}

    public record Order(String id, Customer customer) {}

    public record Profile(String bio, String avatar) {}

    public record Settings(String theme, String language) {}

    public record Document(String content) {}
  }

  @Nested
  @DisplayName("Basic Parameter Reference Examples")
  class BasicParameterReferenceTests {

    @Test
    @DisplayName("Should resolve #{#userId}")
    void shouldResolveUserId() throws Exception {
      Method method = WikiExamples.class.getMethod("updateUser", String.class);
      Object[] args = new Object[] {"user123"};

      String result = SpELKeyResolver.resolve("#{#userId}", method, args);

      assertEquals("user123", result);
    }

    @Test
    @DisplayName("Should resolve #{#orderId}")
    void shouldResolveOrderId() throws Exception {
      Method method = WikiExamples.class.getMethod("processOrder", Long.class);
      Object[] args = new Object[] {12345L};

      String result = SpELKeyResolver.resolve("#{#orderId}", method, args);

      assertEquals("12345", result);
    }
  }

  @Nested
  @DisplayName("String Concatenation Examples")
  class StringConcatenationTests {

    @Test
    @DisplayName("Should resolve #{'user-' + #userId}")
    void shouldConcatenateUserPrefix() throws Exception {
      Method method = WikiExamples.class.getMethod("updateUserWithPrefix", String.class);
      Object[] args = new Object[] {"user123"};

      String result = SpELKeyResolver.resolve("#{'user-' + #userId}", method, args);

      assertEquals("user-user123", result);
    }

    @Test
    @DisplayName("Should resolve #{'order-' + #orderId + '-process'}")
    void shouldConcatenateOrderWithSuffix() throws Exception {
      Method method = WikiExamples.class.getMethod("processOrderWithSuffix", Long.class);
      Object[] args = new Object[] {12345L};

      String result = SpELKeyResolver.resolve("#{'order-' + #orderId + '-process'}", method, args);

      assertEquals("order-12345-process", result);
    }
  }

  @Nested
  @DisplayName("Object Properties Examples")
  class ObjectPropertiesTests {

    @Test
    @DisplayName("Should resolve #{#user.id}")
    void shouldResolveUserId() throws Exception {
      Method method = WikiExamples.class.getMethod("updateUserById", WikiExamples.User.class);
      Object[] args = new Object[] {new WikiExamples.User("123", "John", "john@example.com")};

      String result = SpELKeyResolver.resolve("#{#user.id}", method, args);

      assertEquals("123", result);
    }

    @Test
    @DisplayName("Should resolve #{#order.customer.id}")
    void shouldResolveNestedProperty() throws Exception {
      Method method =
          WikiExamples.class.getMethod("processOrderByCustomerId", WikiExamples.Order.class);
      WikiExamples.Order order =
          new WikiExamples.Order("ord-1", new WikiExamples.Customer("456", "Jane"));
      Object[] args = new Object[] {order};

      String result = SpELKeyResolver.resolve("#{#order.customer.id}", method, args);

      assertEquals("456", result);
    }

    @Test
    @DisplayName("Should resolve #{'user-' + #user.email}")
    void shouldConcatenateUserEmail() throws Exception {
      Method method = WikiExamples.class.getMethod("sendEmail", WikiExamples.User.class);
      Object[] args = new Object[] {new WikiExamples.User("123", "John", "john@example.com")};

      String result = SpELKeyResolver.resolve("#{'user-' + #user.email}", method, args);

      assertEquals("user-john@example.com", result);
    }
  }

  @Nested
  @DisplayName("Multiple Parameters Examples")
  class MultipleParametersTests {

    @Test
    @DisplayName("Should resolve #{#tenantId + '-' + #userId}")
    void shouldCombineMultipleParams() throws Exception {
      Method method =
          WikiExamples.class.getMethod("updateUserInTenant", String.class, String.class);
      Object[] args = new Object[] {"tenant1", "user123"};

      String result = SpELKeyResolver.resolve("#{#tenantId + '-' + #userId}", method, args);

      assertEquals("tenant1-user123", result);
    }

    @Test
    @DisplayName("Should resolve #{'transfer-' + #fromAccount + '-to-' + #toAccount}")
    void shouldCombineTransferAccounts() throws Exception {
      Method method =
          WikiExamples.class.getMethod("transfer", String.class, String.class, BigDecimal.class);
      Object[] args = new Object[] {"ACC001", "ACC002", new BigDecimal("100.00")};

      String result =
          SpELKeyResolver.resolve(
              "#{'transfer-' + #fromAccount + '-to-' + #toAccount}", method, args);

      assertEquals("transfer-ACC001-to-ACC002", result);
    }
  }

  @Nested
  @DisplayName("Parameter by Position Examples")
  class ParameterByPositionTests {

    @Test
    @DisplayName("Should resolve #{#p0}")
    void shouldResolveByP0() throws Exception {
      Method method = WikiExamples.class.getMethod("processByP0", String.class);
      Object[] args = new Object[] {"user123"};

      String result = SpELKeyResolver.resolve("#{#p0}", method, args);

      assertEquals("user123", result);
    }

    @Test
    @DisplayName("Should resolve #{#a0 + '-' + #a1}")
    void shouldResolveByA0A1() throws Exception {
      Method method = WikiExamples.class.getMethod("processByPosition", String.class, String.class);
      Object[] args = new Object[] {"first", "second"};

      String result = SpELKeyResolver.resolve("#{#a0 + '-' + #a1}", method, args);

      assertEquals("first-second", result);
    }
  }

  @Nested
  @DisplayName("Method Calls Examples")
  class MethodCallsTests {

    @Test
    @DisplayName("Should resolve #{#email.toLowerCase()}")
    void shouldCallToLowerCase() throws Exception {
      Method method = WikiExamples.class.getMethod("processEmail", String.class);
      Object[] args = new Object[] {"JOHN@EXAMPLE.COM"};

      String result = SpELKeyResolver.resolve("#{#email.toLowerCase()}", method, args);

      assertEquals("john@example.com", result);
    }

    @Test
    @DisplayName("Should resolve #{#list.size()}")
    void shouldCallSize() throws Exception {
      Method method = WikiExamples.class.getMethod("processList", List.class);
      Object[] args = new Object[] {Arrays.asList("a", "b", "c", "d", "e")};

      String result = SpELKeyResolver.resolve("#{#list.size()}", method, args);

      assertEquals("5", result);
    }

    @Test
    @DisplayName("Should resolve #{#date.toLocalDate().toString()}")
    void shouldCallToLocalDate() throws Exception {
      Method method = WikiExamples.class.getMethod("processForDate", LocalDateTime.class);
      LocalDateTime date = LocalDateTime.of(2024, 1, 15, 14, 30);
      Object[] args = new Object[] {date};

      String result = SpELKeyResolver.resolve("#{#date.toLocalDate().toString()}", method, args);

      assertEquals("2024-01-15", result);
    }

    @Test
    @DisplayName("Should resolve #{#user.getId()}")
    void shouldCallGetId() throws Exception {
      Method method = WikiExamples.class.getMethod("processUserGetId", WikiExamples.User.class);
      Object[] args = new Object[] {new WikiExamples.User("user-456", "Alice", "alice@test.com")};

      String result = SpELKeyResolver.resolve("#{#user.getId()}", method, args);

      assertEquals("user-456", result);
    }
  }

  @Nested
  @DisplayName("Conditional Expressions Examples")
  class ConditionalExpressionsTests {

    @Test
    @DisplayName("Should resolve conditional with premium=true")
    void shouldResolveConditionalPremiumTrue() throws Exception {
      Method method =
          WikiExamples.class.getMethod("processUserPremium", String.class, boolean.class);
      Object[] args = new Object[] {"user123", true};

      String result =
          SpELKeyResolver.resolve(
              "#{#premium ? 'premium-' + #userId : 'standard-' + #userId}", method, args);

      assertEquals("premium-user123", result);
    }

    @Test
    @DisplayName("Should resolve conditional with premium=false")
    void shouldResolveConditionalPremiumFalse() throws Exception {
      Method method =
          WikiExamples.class.getMethod("processUserPremium", String.class, boolean.class);
      Object[] args = new Object[] {"user123", false};

      String result =
          SpELKeyResolver.resolve(
              "#{#premium ? 'premium-' + #userId : 'standard-' + #userId}", method, args);

      assertEquals("standard-user123", result);
    }

    @Test
    @DisplayName("Should resolve #{#amount > 1000 ? 'large' : 'small'} with large amount")
    void shouldResolveAmountLarge() throws Exception {
      Method method = WikiExamples.class.getMethod("processPayment", double.class);
      Object[] args = new Object[] {1500.0};

      String result =
          SpELKeyResolver.resolve("#{#amount > 1000 ? 'large' : 'small'}", method, args);

      assertEquals("large", result);
    }

    @Test
    @DisplayName("Should resolve #{#amount > 1000 ? 'large' : 'small'} with small amount")
    void shouldResolveAmountSmall() throws Exception {
      Method method = WikiExamples.class.getMethod("processPayment", double.class);
      Object[] args = new Object[] {500.0};

      String result =
          SpELKeyResolver.resolve("#{#amount > 1000 ? 'large' : 'small'}", method, args);

      assertEquals("small", result);
    }
  }

  @Nested
  @DisplayName("Null Safety Examples")
  class NullSafetyTests {

    @Test
    @DisplayName("Should resolve #{#user.nickname ?: #user.id} with nickname")
    void shouldUseNickname() throws Exception {
      Method method = WikiExamples.class.getMethod("updateUserWithElvis", WikiExamples.User.class);
      Object[] args =
          new Object[] {new WikiExamples.User("123", "John", "john@test.com", "Johnny")};

      String result = SpELKeyResolver.resolve("#{#user.nickname ?: #user.id}", method, args);

      assertEquals("Johnny", result);
    }

    @Test
    @DisplayName("Should resolve #{#user.nickname ?: #user.id} without nickname")
    void shouldFallbackToId() throws Exception {
      Method method = WikiExamples.class.getMethod("updateUserWithElvis", WikiExamples.User.class);
      Object[] args = new Object[] {new WikiExamples.User("123", "John", "john@test.com", null)};

      String result = SpELKeyResolver.resolve("#{#user.nickname ?: #user.id}", method, args);

      assertEquals("123", result);
    }

    @Test
    @DisplayName("Should resolve #{#order?.customer?.id ?: 'anonymous'} with order")
    void shouldResolveSafeNavigationWithOrder() throws Exception {
      Method method =
          WikiExamples.class.getMethod("processOrderSafeNavigation", WikiExamples.Order.class);
      WikiExamples.Order order =
          new WikiExamples.Order("ord-1", new WikiExamples.Customer("cust-456", "Customer"));
      Object[] args = new Object[] {order};

      String result =
          SpELKeyResolver.resolve("#{#order?.customer?.id ?: 'anonymous'}", method, args);

      assertEquals("cust-456", result);
    }

    @Test
    @DisplayName("Should resolve #{#order?.customer?.id ?: 'anonymous'} with null order")
    void shouldResolveSafeNavigationWithNullOrder() throws Exception {
      Method method =
          WikiExamples.class.getMethod("processOrderSafeNavigation", WikiExamples.Order.class);
      Object[] args = new Object[] {null};

      String result =
          SpELKeyResolver.resolve("#{#order?.customer?.id ?: 'anonymous'}", method, args);

      assertEquals("anonymous", result);
    }
  }

  @Nested
  @DisplayName("Array and Collection Access Examples")
  class ArrayCollectionAccessTests {

    @Test
    @DisplayName("Should resolve #{#ids[0]}")
    void shouldAccessFirstElement() throws Exception {
      Method method = WikiExamples.class.getMethod("processFirst", List.class);
      Object[] args = new Object[] {Arrays.asList("first-id", "second-id")};

      String result = SpELKeyResolver.resolve("#{#ids[0]}", method, args);

      assertEquals("first-id", result);
    }

    @Test
    @DisplayName("Should resolve #{#args[0] + '-' + #args[1]}")
    void shouldAccessVarargsElements() throws Exception {
      Method method = WikiExamples.class.getMethod("processVarargs", String[].class);
      Object[] args = new Object[] {new String[] {"arg1", "arg2", "arg3"}};

      String result = SpELKeyResolver.resolve("#{#args[0] + '-' + #args[1]}", method, args);

      assertEquals("arg1-arg2", result);
    }

    @Test
    @DisplayName("Should resolve #{#users[0].name}")
    void shouldAccessObjectInCollection() throws Exception {
      Method method = WikiExamples.class.getMethod("processFirstUser", List.class);
      List<WikiExamples.User> users = List.of(new WikiExamples.User("1", "John", "john@test.com"));
      Object[] args = new Object[] {users};

      String result = SpELKeyResolver.resolve("#{#users[0].name}", method, args);

      assertEquals("John", result);
    }
  }

  @Nested
  @DisplayName("Map Access Examples")
  class MapAccessTests {

    @Test
    @DisplayName("Should resolve #{#params['orderId']}")
    void shouldAccessMapValue() throws Exception {
      Method method = WikiExamples.class.getMethod("processMap", Map.class);
      Object[] args = new Object[] {Map.of("orderId", "order123")};

      String result = SpELKeyResolver.resolve("#{#params['orderId']}", method, args);

      assertEquals("order123", result);
    }

    @Test
    @DisplayName("Should resolve #{#context['tenant'] + '-' + #context['user']}")
    void shouldCombineMapValues() throws Exception {
      Method method = WikiExamples.class.getMethod("processInContext", Map.class);
      Object[] args = new Object[] {Map.of("tenant", "tenant1", "user", "user123")};

      String result =
          SpELKeyResolver.resolve("#{#context['tenant'] + '-' + #context['user']}", method, args);

      assertEquals("tenant1-user123", result);
    }
  }

  @Nested
  @DisplayName("Static Methods Examples")
  class StaticMethodsTests {

    @Test
    @DisplayName("Should resolve #{T(java.lang.String).valueOf(#orderId)}")
    void shouldCallStaticValueOf() throws Exception {
      Method method = WikiExamples.class.getMethod("processWithStaticMethod", Long.class);
      Object[] args = new Object[] {12345L};

      String result =
          SpELKeyResolver.resolve("#{T(java.lang.String).valueOf(#orderId)}", method, args);

      assertEquals("12345", result);
    }

    @Test
    @DisplayName("Should resolve #{T(java.lang.Math).max(#a, #b)}")
    void shouldCallStaticMax() throws Exception {
      Method method = WikiExamples.class.getMethod("processMax", int.class, int.class);
      Object[] args = new Object[] {42, 100};

      String result = SpELKeyResolver.resolve("#{T(java.lang.Math).max(#a, #b)}", method, args);

      assertEquals("100", result);
    }
  }

  @Nested
  @DisplayName("Common Patterns Examples")
  class CommonPatternsTests {

    @Test
    @DisplayName("Should resolve #{'user-profile-' + #userId}")
    void shouldResolveUserProfile() throws Exception {
      Method method =
          WikiExamples.class.getMethod("updateProfile", String.class, WikiExamples.Profile.class);
      Object[] args = new Object[] {"user123", new WikiExamples.Profile("Bio", "avatar.png")};

      String result = SpELKeyResolver.resolve("#{'user-profile-' + #userId}", method, args);

      assertEquals("user-profile-user123", result);
    }

    @Test
    @DisplayName("Should resolve #{'user-settings-' + #user.id}")
    void shouldResolveUserSettings() throws Exception {
      Method method =
          WikiExamples.class.getMethod(
              "updateSettings", WikiExamples.User.class, WikiExamples.Settings.class);
      Object[] args =
          new Object[] {
            new WikiExamples.User("456", "Alice", "alice@test.com"),
            new WikiExamples.Settings("dark", "en")
          };

      String result = SpELKeyResolver.resolve("#{'user-settings-' + #user.id}", method, args);

      assertEquals("user-settings-456", result);
    }

    @Test
    @DisplayName("Should resolve #{'document-' + #documentId}")
    void shouldResolveDocument() throws Exception {
      Method method = WikiExamples.class.getMethod("editDocument", String.class, String.class);
      Object[] args = new Object[] {"doc-789", "content"};

      String result = SpELKeyResolver.resolve("#{'document-' + #documentId}", method, args);

      assertEquals("document-doc-789", result);
    }

    @Test
    @DisplayName("Should resolve #{'file-' + #path.hashCode()}")
    void shouldResolveFileHash() throws Exception {
      Method method = WikiExamples.class.getMethod("writeFile", Path.class, byte[].class);
      Path path = Path.of("/tmp/test.txt");
      Object[] args = new Object[] {path, new byte[] {1, 2, 3}};

      String result = SpELKeyResolver.resolve("#{'file-' + #path.hashCode()}", method, args);

      assertEquals("file-" + path.hashCode(), result);
    }

    @Test
    @DisplayName("Should resolve #{#tenantId + ':user:' + #userId}")
    void shouldResolveTenantUser() throws Exception {
      Method method =
          WikiExamples.class.getMethod("updateTenantUserColon", String.class, String.class);
      Object[] args = new Object[] {"tenant1", "user123"};

      String result = SpELKeyResolver.resolve("#{#tenantId + ':user:' + #userId}", method, args);

      assertEquals("tenant1:user:user123", result);
    }

    @Test
    @DisplayName("Should resolve #{'inventory-' + #warehouseId + '-' + #productId}")
    void shouldResolveInventory() throws Exception {
      Method method =
          WikiExamples.class.getMethod("updateInventory", String.class, String.class, int.class);
      Object[] args = new Object[] {"WH001", "PROD123", 50};

      String result =
          SpELKeyResolver.resolve(
              "#{'inventory-' + #warehouseId + '-' + #productId}", method, args);

      assertEquals("inventory-WH001-PROD123", result);
    }

    @Test
    @DisplayName("Should resolve hourly task with truncated instant")
    void shouldResolveHourlyTask() throws Exception {
      Method method = WikiExamples.class.getMethod("hourlyTask", Instant.class);
      Instant instant = Instant.parse("2024-01-15T14:30:45Z");
      Object[] args = new Object[] {instant};

      String result =
          SpELKeyResolver.resolve(
              "#{'hourly-' + #instant.truncatedTo(T(java.time.temporal.ChronoUnit).HOURS)}",
              method,
              args);

      assertEquals("hourly-" + instant.truncatedTo(ChronoUnit.HOURS), result);
    }
  }

  @Nested
  @DisplayName("Literal Keys with # Character Examples")
  class LiteralKeysTests {

    @Test
    @DisplayName("Should treat 'order#123' as literal")
    void shouldTreatOrderHashAsLiteral() throws Exception {
      Method method = WikiExamples.class.getMethod("processOrderLiteral");
      Object[] args = new Object[] {};

      String result = SpELKeyResolver.resolve("order#123", method, args);

      assertEquals("order#123", result);
    }

    @Test
    @DisplayName("Should treat 'task#end' as literal")
    void shouldTreatTaskHashAsLiteral() throws Exception {
      Method method = WikiExamples.class.getMethod("endTask");
      Object[] args = new Object[] {};

      String result = SpELKeyResolver.resolve("task#end", method, args);

      assertEquals("task#end", result);
    }

    @Test
    @DisplayName("Should treat 'item-#1' as literal")
    void shouldTreatItemHashAsLiteral() throws Exception {
      Method method = WikiExamples.class.getMethod("processItem");
      Object[] args = new Object[] {};

      String result = SpELKeyResolver.resolve("item-#1", method, args);

      assertEquals("item-#1", result);
    }

    @Test
    @DisplayName("Should treat 'prefix#middle#suffix' as literal")
    void shouldTreatMultipleHashesAsLiteral() throws Exception {
      Method method = WikiExamples.class.getMethod("processMultipleHashes");
      Object[] args = new Object[] {};

      String result = SpELKeyResolver.resolve("prefix#middle#suffix", method, args);

      assertEquals("prefix#middle#suffix", result);
    }
  }

  @Nested
  @DisplayName("Best Practices Examples")
  class BestPracticesTests {

    @Test
    @DisplayName("Should normalize email with toLowerCase and trim")
    void shouldNormalizeEmail() throws Exception {
      Method method = WikiExamples.class.getMethod("processEmailNormalized", String.class);
      Object[] args = new Object[] {"  JOHN@EXAMPLE.COM  "};

      String result =
          SpELKeyResolver.resolve("#{'email-' + #email.toLowerCase().trim()}", method, args);

      assertEquals("email-john@example.com", result);
    }

    @Test
    @DisplayName("Should use prefix 'read:' for read operations")
    void shouldUseReadPrefix() throws Exception {
      Method method = WikiExamples.class.getMethod("readDocument", String.class);
      Object[] args = new Object[] {"doc-123"};

      String result = SpELKeyResolver.resolve("#{'read:' + #docId}", method, args);

      assertEquals("read:doc-123", result);
    }

    @Test
    @DisplayName("Should use prefix 'write:' for write operations")
    void shouldUseWritePrefix() throws Exception {
      Method method =
          WikiExamples.class.getMethod("writeDocument", String.class, WikiExamples.Document.class);
      Object[] args = new Object[] {"doc-123", new WikiExamples.Document("content")};

      String result = SpELKeyResolver.resolve("#{'write:' + #docId}", method, args);

      assertEquals("write:doc-123", result);
    }
  }
}
