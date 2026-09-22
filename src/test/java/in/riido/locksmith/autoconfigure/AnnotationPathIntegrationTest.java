package in.riido.locksmith.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.redis.testcontainers.RedisContainer;
import in.riido.locksmith.DistributedLock;
import in.riido.locksmith.DockerAvailableCondition;
import in.riido.locksmith.OnFailure;
import in.riido.locksmith.lock.LockFailureContext;
import in.riido.locksmith.lock.LockFailureHandler;
import in.riido.locksmith.lock.LockNotAcquiredException;
import java.util.Optional;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.redisson.Redisson;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.aop.support.AopUtils;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.aop.AopAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.testcontainers.utility.DockerImageName;

/** {@code @DistributedLock} end to end: a Boot context without AspectJ against a real Redis. */
@ExtendWith(DockerAvailableCondition.class)
@DisplayName("@DistributedLock end to end")
class AnnotationPathIntegrationTest {

  private static final String FULL_KEY = "locksmith:lock:order:42";
  private static final String MARKER = "handled";

  private static RedisContainer redis;
  private static RedissonClient otherInstance;

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withConfiguration(
              AutoConfigurations.of(AopAutoConfiguration.class, LocksmithAutoConfiguration.class))
          .withUserConfiguration(UserConfiguration.class);

  private RLock heldElsewhere;

  @BeforeAll
  static void startRedis() {
    redis = new RedisContainer(DockerImageName.parse("redis:7-alpine"));
    redis.start();
    otherInstance = newClient();
  }

  @AfterAll
  static void stopRedis() {
    otherInstance.shutdown();
    redis.stop();
  }

  @BeforeEach
  void lockOfOtherInstance() {
    heldElsewhere = otherInstance.getLock(FULL_KEY);
  }

  @AfterEach
  void releaseElsewhere() {
    if (heldElsewhere.isHeldByCurrentThread()) {
      heldElsewhere.unlock();
    }
  }

  private static RedissonClient newClient() {
    Config config = new Config();
    config
        .useSingleServer()
        .setAddress("redis://" + redis.getHost() + ":" + redis.getFirstMappedPort());
    return Redisson.create(config);
  }

  @Configuration(proxyBeanMethods = false)
  static class UserConfiguration {

    @Bean(destroyMethod = "shutdown")
    RedissonClient redissonClient() {
      return newClient();
    }

    @Bean
    MarkerHandler markerHandler() {
      return new MarkerHandler();
    }

    @Bean
    OrderService orderService() {
      return new OrderService();
    }
  }

  static class MarkerHandler implements LockFailureHandler {
    @Override
    public Object onFailure(LockFailureContext context) {
      return MARKER;
    }
  }

  static class OrderService {
    @DistributedLock(key = "order:#{#id}")
    public String process(String id) {
      return "processed " + id;
    }

    @DistributedLock(key = "order:#{#id}", onFailure = OnFailure.SKIP)
    public Optional<String> processOrSkip(String id) {
      return Optional.of("processed " + id);
    }

    @DistributedLock(
        key = "order:#{#id}",
        onFailure = OnFailure.HANDLER,
        handler = MarkerHandler.class)
    public Object processOrHandle(String id) {
      return "processed " + id;
    }
  }

  @Test
  @DisplayName("the service bean is proxied although AspectJ is absent")
  void proxied() {
    runner.run(
        context -> assertThat(AopUtils.isAopProxy(context.getBean(OrderService.class))).isTrue());
  }

  @Test
  @DisplayName("a free lock: the method runs and releases, so a second call runs too")
  void runsAndReleases() {
    runner.run(
        context -> {
          OrderService service = context.getBean(OrderService.class);

          assertThat(service.process("42")).isEqualTo("processed 42");
          assertThat(service.process("42")).isEqualTo("processed 42");
          assertThat(heldElsewhere.isLocked()).isFalse();
        });
  }

  @Test
  @DisplayName("a lock held by another instance: THROW, SKIP and HANDLER apply their policy")
  void heldElsewhere() {
    runner.run(
        context -> {
          OrderService service = context.getBean(OrderService.class);
          heldElsewhere.lock();

          assertThatThrownBy(() -> service.process("42"))
              .isInstanceOf(LockNotAcquiredException.class)
              .hasMessageContaining(FULL_KEY);
          assertThat(service.processOrSkip("42")).isEmpty();
          assertThat(service.processOrHandle("42")).isEqualTo(MARKER);

          heldElsewhere.unlock();
          assertThat(service.process("42")).isEqualTo("processed 42");
        });
  }
}
