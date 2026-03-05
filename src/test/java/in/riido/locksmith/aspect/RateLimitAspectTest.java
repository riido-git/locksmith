package in.riido.locksmith.aspect;

import static org.junit.jupiter.api.Assertions.*;

import in.riido.locksmith.AcquisitionMode;
import in.riido.locksmith.RateLimit;
import in.riido.locksmith.autoconfigure.LocksmithProperties;
import in.riido.locksmith.handler.ratelimit.RateLimitReturnDefaultHandler;
import in.riido.locksmith.handler.ratelimit.RateLimitThrowExceptionHandler;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RateType;
import org.redisson.api.RedissonClient;
import org.springframework.context.ApplicationContext;

/**
 * Unit tests for RateLimitAspect construction and configuration.
 *
 * <p>Full functional tests are in RateLimitIntegrationTest which tests against real Redis.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("RateLimitAspect Unit Tests")
class RateLimitAspectTest {

  @Mock private RedissonClient redissonClient;
  @Mock private ApplicationContext applicationContext;

  private RateLimitAspect aspect;
  private LocksmithProperties properties;

  @BeforeEach
  void setUp() {
    properties =
        new LocksmithProperties(
            null,
            null,
            new LocksmithProperties.RateLimitProperties(
                true, Duration.ofMinutes(1), "ratelimit:", false, false));
    aspect = new RateLimitAspect(redissonClient, properties, applicationContext);
  }

  @Nested
  @DisplayName("Construction Tests")
  class ConstructionTests {

    @Test
    @DisplayName("Should create aspect with required dependencies")
    void shouldCreateAspectWithRequiredDependencies() {
      assertNotNull(aspect);
    }

    @Test
    @DisplayName("Should create aspect with optional metrics")
    void shouldCreateAspectWithOptionalMetrics() {
      LocksmithProperties metricsProps =
          new LocksmithProperties(
              null,
              null,
              new LocksmithProperties.RateLimitProperties(
                  true, Duration.ofMinutes(1), "ratelimit:", false, true));
      RateLimitAspect aspectWithMetrics =
          new RateLimitAspect(redissonClient, metricsProps, applicationContext, null);

      assertNotNull(aspectWithMetrics);
    }

    @Test
    @DisplayName("Should use properties from configuration")
    void shouldUsePropertiesFromConfiguration() {
      LocksmithProperties customProps =
          new LocksmithProperties(
              null,
              null,
              new LocksmithProperties.RateLimitProperties(
                  true, Duration.ofSeconds(30), "custom:", true, true));
      RateLimitAspect customAspect =
          new RateLimitAspect(redissonClient, customProps, applicationContext);

      assertNotNull(customAspect);
    }
  }

  @Nested
  @DisplayName("Annotation Configuration Tests")
  class AnnotationConfigurationTests {

    @Test
    @DisplayName("Should have default values in annotation")
    void annotationShouldHaveDefaultValues() throws NoSuchMethodException {
      RateLimit annotation =
          TestService.class.getMethod("defaultsMethod").getAnnotation(RateLimit.class);

      assertEquals("default-key", annotation.key());
      assertEquals(10, annotation.permits());
      assertEquals("1s", annotation.interval());
      assertEquals(RateType.OVERALL, annotation.type());
      assertEquals(AcquisitionMode.SKIP_IMMEDIATELY, annotation.mode());
      assertEquals("", annotation.waitTime());
      assertEquals(RateLimitThrowExceptionHandler.class, annotation.skipHandler());
    }

    @Test
    @DisplayName("Should support custom permits and interval")
    void annotationShouldSupportCustomPermitsAndInterval() throws NoSuchMethodException {
      RateLimit annotation =
          TestService.class.getMethod("customRateMethod").getAnnotation(RateLimit.class);

      assertEquals("custom-rate", annotation.key());
      assertEquals(100, annotation.permits());
      assertEquals("1m", annotation.interval());
    }

    @Test
    @DisplayName("Should support WAIT_AND_SKIP mode")
    void annotationShouldSupportWaitMode() throws NoSuchMethodException {
      RateLimit annotation =
          TestService.class.getMethod("waitModeMethod").getAnnotation(RateLimit.class);

      assertEquals(AcquisitionMode.WAIT_AND_SKIP, annotation.mode());
      assertEquals("5s", annotation.waitTime());
    }

    @Test
    @DisplayName("Should support PER_CLIENT rate type")
    void annotationShouldSupportPerClientRateType() throws NoSuchMethodException {
      RateLimit annotation =
          TestService.class.getMethod("perClientMethod").getAnnotation(RateLimit.class);

      assertEquals(RateType.PER_CLIENT, annotation.type());
    }

    @Test
    @DisplayName("Should support ReturnDefaultHandler")
    void annotationShouldSupportReturnDefaultHandler() throws NoSuchMethodException {
      RateLimit annotation =
          TestService.class.getMethod("returnDefaultMethod").getAnnotation(RateLimit.class);

      assertEquals(RateLimitReturnDefaultHandler.class, annotation.skipHandler());
    }

    @Test
    @DisplayName("Should support SpEL expression in key")
    void annotationShouldSupportSpelExpressionInKey() throws NoSuchMethodException {
      RateLimit annotation =
          TestService.class.getMethod("spelKeyMethod", String.class).getAnnotation(RateLimit.class);

      assertEquals("#{#userId}", annotation.key());
    }
  }

  // Test service class for annotation inspection
  public static class TestService {

    @RateLimit(key = "default-key")
    public String defaultsMethod() {
      return "result";
    }

    @RateLimit(key = "custom-rate", permits = 100, interval = "1m")
    public String customRateMethod() {
      return "result";
    }

    @RateLimit(key = "wait-mode", mode = AcquisitionMode.WAIT_AND_SKIP, waitTime = "5s")
    public String waitModeMethod() {
      return "result";
    }

    @RateLimit(key = "per-client", type = RateType.PER_CLIENT)
    public String perClientMethod() {
      return "result";
    }

    @RateLimit(key = "return-default", skipHandler = RateLimitReturnDefaultHandler.class)
    public String returnDefaultMethod() {
      return "result";
    }

    @RateLimit(key = "#{#userId}")
    public String spelKeyMethod(String userId) {
      return "result";
    }
  }
}
