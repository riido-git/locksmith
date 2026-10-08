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
import java.lang.reflect.Proxy;
import java.util.Optional;
import org.aopalliance.intercept.MethodInterceptor;
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
import org.springframework.aop.framework.ProxyFactory;
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

  /** A bean that is itself a JDK dynamic proxy, as a Feign or HTTP interface client is. */
  private static <T> T jdkProxy(Class<T> type) {
    CheckLock implementation = new CheckLockImpl();
    return type.cast(
        Proxy.newProxyInstance(
            type.getClassLoader(),
            new Class<?>[] {type},
            (proxy, method, args) ->
                method.getDeclaringClass() == Object.class
                    ? method.invoke(implementation, args)
                    : implementation.lockedDuringCall((String) args[0])));
  }

  /**
   * A Spring proxy shaped like a Spring Data repository: its target class does not implement the
   * annotated interface, and an advice answers the call, as a repository's own interceptors do.
   */
  private static <T> T springDataShaped(Class<T> repositoryInterface) {
    ProxyFactory factory = new ProxyFactory(new CheckLockImpl());
    factory.setInterfaces(repositoryInterface);
    factory.addAdvice(
        (MethodInterceptor)
            invocation ->
                invocation.getMethod().getName().equals("lockedDuringCall")
                    ? ((CheckLock) invocation.getThis())
                        .lockedDuringCall((String) invocation.getArguments()[0])
                    : invocation.proceed());
    return repositoryInterface.cast(factory.getProxy());
  }

  interface OrderClient {
    /** Returns whether the order's lock is held while the call runs. */
    @DistributedLock(key = "order:#{#id}")
    boolean lockedDuringCall(String id);
  }

  /** Plays the part of CrudRepository. */
  interface CheckLock {
    boolean lockedDuringCall(String id);
  }

  /** Plays the part of a repository interface that redeclares an inherited method. */
  interface OrderRepository extends CheckLock {
    @Override
    @DistributedLock(key = "order:#{#id}")
    boolean lockedDuringCall(String id);
  }

  /** The annotated declaration, in an interface unrelated to CheckLock. */
  interface LockedCheck {
    @DistributedLock(key = "order:#{#id}")
    boolean lockedDuringCall(String id);
  }

  /** Inherits the method from two unrelated interfaces, the annotated one second. */
  interface DiamondClient extends CheckLock, LockedCheck {}

  /** The same, as a repository interface. */
  interface DiamondRepository extends CheckLock, LockedCheck {}

  /** Plays the part of SimpleJpaRepository: it implements CheckLock, not OrderRepository. */
  static class CheckLockImpl implements CheckLock {
    @Override
    public boolean lockedDuringCall(String id) {
      return otherInstance.getLock(FULL_KEY).isLocked();
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

  @Test
  @DisplayName(
      "a bean that is itself a JDK proxy: locked during the call, with the key from its interface")
  void jdkProxyBean() {
    runner
        .withBean(OrderClient.class, () -> jdkProxy(OrderClient.class))
        .run(
            context -> {
              OrderClient client = context.getBean(OrderClient.class);

              assertThat(client.lockedDuringCall("42")).isTrue();
              assertThat(heldElsewhere.isLocked()).isFalse();

              heldElsewhere.lock();
              assertThatThrownBy(() -> client.lockedDuringCall("42"))
                  .isInstanceOf(LockNotAcquiredException.class)
                  .hasMessageContaining(FULL_KEY);
            });
  }

  @Test
  @DisplayName(
      "a Spring proxy shaped like a Spring Data repository: locked during the call of a method"
          + " its target class also declares")
  void springDataShapedBean() {
    runner
        .withBean(OrderRepository.class, () -> springDataShaped(OrderRepository.class))
        .run(
            context -> {
              OrderRepository repository = context.getBean(OrderRepository.class);

              assertThat(repository.lockedDuringCall("42")).isTrue();
              assertThat(heldElsewhere.isLocked()).isFalse();

              heldElsewhere.lock();
              assertThatThrownBy(() -> repository.lockedDuringCall("42"))
                  .isInstanceOf(LockNotAcquiredException.class)
                  .hasMessageContaining(FULL_KEY);
            });
  }

  @Test
  @DisplayName(
      "a JDK proxy over an interface that inherits the method from two unrelated interfaces, the"
          + " annotated one second: locked during the call")
  void diamondJdkProxyBean() {
    runner
        .withBean(DiamondClient.class, () -> jdkProxy(DiamondClient.class))
        .run(
            context ->
                assertThat(context.getBean(DiamondClient.class).lockedDuringCall("42")).isTrue());
  }

  @Test
  @DisplayName(
      "the same diamond on a Spring Data shaped proxy, whose target implements the unannotated"
          + " interface: locked during the call")
  void diamondSpringDataShapedBean() {
    runner
        .withBean(DiamondRepository.class, () -> springDataShaped(DiamondRepository.class))
        .run(
            context ->
                assertThat(context.getBean(DiamondRepository.class).lockedDuringCall("42"))
                    .isTrue());
  }
}
