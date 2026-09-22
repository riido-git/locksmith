package in.riido.locksmith.autoconfigure;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;

import com.redis.testcontainers.RedisContainer;
import in.riido.locksmith.DistributedLock;
import in.riido.locksmith.DistributedSemaphore;
import in.riido.locksmith.DockerAvailableCondition;
import in.riido.locksmith.semaphore.SemaphoreNotAcquiredException;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.redisson.Redisson;
import org.redisson.api.RLock;
import org.redisson.api.RPermitExpirableSemaphore;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.aop.AopAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.testcontainers.utility.DockerImageName;

/**
 * {@code @DistributedSemaphore} end to end, alone and together with {@code @DistributedLock}: a
 * Boot context without AspectJ against a real Redis.
 */
@ExtendWith(DockerAvailableCondition.class)
@DisplayName("@DistributedSemaphore end to end")
class SemaphoreAnnotationPathIntegrationTest {

  private static final String SEMAPHORE_KEY = "locksmith:semaphore:report:42";

  private static RedisContainer redis;
  private static RedissonClient otherInstance;

  /** The Redisson objects the context's client handed out, wrapped to record calls. */
  private static final AtomicReference<RPermitExpirableSemaphore> recordedSemaphore =
      new AtomicReference<>();

  private static final AtomicReference<RLock> recordedLock = new AtomicReference<>();

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withConfiguration(
              AutoConfigurations.of(AopAutoConfiguration.class, LocksmithAutoConfiguration.class))
          .withUserConfiguration(UserConfiguration.class);

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
  void clearRedis() {
    otherInstance.getKeys().flushall();
    recordedSemaphore.set(null);
    recordedLock.set(null);
  }

  private static RedissonClient newClient() {
    Config config = new Config();
    config
        .useSingleServer()
        .setAddress("redis://" + redis.getHost() + ":" + redis.getFirstMappedPort());
    return Redisson.create(config);
  }

  /**
   * Wraps a real client so that every semaphore and lock it returns is a Mockito spy, recorded for
   * the call-order assertion.
   */
  private static RedissonClient recordingClient() {
    RedissonClient real = newClient();
    RedissonClient wrapper = mock(RedissonClient.class, delegatesTo(real));
    doAnswer(
            invocation -> {
              RPermitExpirableSemaphore semaphore =
                  spy(real.getPermitExpirableSemaphore(invocation.<String>getArgument(0)));
              recordedSemaphore.set(semaphore);
              return semaphore;
            })
        .when(wrapper)
        .getPermitExpirableSemaphore(anyString());
    doAnswer(
            invocation -> {
              RLock lock = spy(real.getLock(invocation.<String>getArgument(0)));
              recordedLock.set(lock);
              return lock;
            })
        .when(wrapper)
        .getLock(anyString());
    return wrapper;
  }

  @Configuration(proxyBeanMethods = false)
  static class UserConfiguration {

    @Bean(destroyMethod = "shutdown")
    RedissonClient redissonClient() {
      return recordingClient();
    }

    @Bean
    ReportService reportService() {
      return new ReportService();
    }
  }

  static class ReportService {
    @DistributedSemaphore(key = "report:#{#id}", permits = "2")
    @DistributedLock(key = "report:#{#id}")
    public String build(String id) {
      return "built " + id;
    }

    @DistributedSemaphore(key = "report:#{#id}", permits = "1")
    public String buildBounded(String id) {
      return "built " + id;
    }
  }

  @Test
  @DisplayName("both annotations: semaphore acquire, lock acquire, lock release, semaphore release")
  void callOrder() {
    runner.run(
        context -> {
          assertThat(context.getBean(ReportService.class).build("42")).isEqualTo("built 42");

          RPermitExpirableSemaphore semaphore = recordedSemaphore.get();
          RLock lock = recordedLock.get();
          InOrder order = inOrder(semaphore, lock);
          order.verify(semaphore).tryAcquire(anyLong(), anyLong(), any());
          order.verify(lock).tryLock(0L, -1L, MILLISECONDS);
          order.verify(lock).unlock();
          order.verify(semaphore).release(anyString());
          assertThat(otherInstance.getLock("locksmith:lock:report:42").isLocked()).isFalse();
          assertThat(otherInstance.getPermitExpirableSemaphore(SEMAPHORE_KEY).availablePermits())
              .isEqualTo(2);
        });
  }

  @Test
  @DisplayName("THROW: permits exhausted by another client give SemaphoreNotAcquiredException")
  void exhaustedByOtherClient() {
    RPermitExpirableSemaphore held = otherInstance.getPermitExpirableSemaphore(SEMAPHORE_KEY);
    held.trySetPermits(1);
    runner.run(
        context -> {
          String permitId = held.tryAcquire(0, 30_000, MILLISECONDS);
          assertThat(permitId).isNotNull();
          ReportService service = context.getBean(ReportService.class);

          assertThatThrownBy(() -> service.buildBounded("42"))
              .isInstanceOf(SemaphoreNotAcquiredException.class)
              .hasMessageContaining(SEMAPHORE_KEY);

          held.release(permitId);
          assertThat(service.buildBounded("42")).isEqualTo("built 42");
        });
  }
}
