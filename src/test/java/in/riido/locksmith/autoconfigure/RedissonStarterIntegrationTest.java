package in.riido.locksmith.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import com.redis.testcontainers.RedisContainer;
import in.riido.locksmith.DistributedLock;
import in.riido.locksmith.DockerAvailableCondition;
import in.riido.locksmith.LockType;
import in.riido.locksmith.lock.LockOperations;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.testcontainers.utility.DockerImageName;

/**
 * Locksmith with the {@code RedissonClient} from {@code redisson-spring-boot-starter}, the setup
 * the README recommends. The full auto-configuration ordering runs, so Locksmith must be evaluated
 * after the starter's auto-configuration or its {@code @ConditionalOnBean} finds no client.
 */
@ExtendWith(DockerAvailableCondition.class)
@DisplayName("Locksmith with the Redisson Spring Boot starter")
class RedissonStarterIntegrationTest {

  private static RedisContainer redis;

  @BeforeAll
  static void startRedis() {
    redis = new RedisContainer(DockerImageName.parse("redis:7-alpine"));
    redis.start();
  }

  @AfterAll
  static void stopRedis() {
    redis.stop();
  }

  @SpringBootConfiguration
  @EnableAutoConfiguration
  static class Application {

    @Bean
    OrderService orderService(LockOperations locks) {
      return new OrderService(locks);
    }
  }

  static class OrderService {

    private final LockOperations locks;

    OrderService(LockOperations locks) {
      this.locks = locks;
    }

    @DistributedLock(key = "order:#{#id}")
    public boolean lockedWhileRunning(String id) {
      return locks.isLocked("order:" + id, LockType.REENTRANT);
    }
  }

  @Test
  @DisplayName("registers Locksmith and locks an annotated method")
  void locksWithStarterClient() {
    SpringApplication application = new SpringApplication(Application.class);
    application.setWebApplicationType(WebApplicationType.NONE);
    try (ConfigurableApplicationContext context =
        application.run(
            "--spring.data.redis.host=" + redis.getHost(),
            "--spring.data.redis.port=" + redis.getFirstMappedPort())) {
      assertThat(context.getBean(OrderService.class).lockedWhileRunning("42")).isTrue();
    }
  }
}
