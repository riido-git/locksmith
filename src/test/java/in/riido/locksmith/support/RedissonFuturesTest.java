package in.riido.locksmith.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import in.riido.locksmith.LocksmithConfigurationException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;

@DisplayName("RedissonFutures")
class RedissonFuturesTest {

  /** Runs the call on a thread with the given name; returns its result, or what it threw. */
  private static Object onThread(String name, Supplier<?> call) throws InterruptedException {
    AtomicReference<Object> outcome = new AtomicReference<>();
    Thread thread =
        new Thread(
            () -> {
              try {
                outcome.set(call.get());
              } catch (RuntimeException e) {
                outcome.set(e);
              }
            },
            name);
    thread.start();
    thread.join();
    return outcome.get();
  }

  private static Object guard(String threadName, boolean waits) throws InterruptedException {
    return onThread(
        threadName,
        () -> {
          RedissonFutures.requireNotRedissonThread(waits);
          return "allowed";
        });
  }

  @Nested
  @DisplayName("requireNotRedissonThread")
  class RequireNotRedissonThread {

    @ParameterizedTest(name = "waits={0}")
    @ValueSource(booleans = {false, true})
    @DisplayName("an I/O thread is refused with Redisson's own message, waiting or not")
    void ioThread(boolean waits) throws InterruptedException {
      assertThat((Throwable) guard("redisson-netty-2-1", waits))
          .isExactlyInstanceOf(IllegalStateException.class)
          .hasMessage("Sync methods can't be invoked from async/rx/reactive listeners");
    }

    @ParameterizedTest(name = "waits={0}")
    @ValueSource(booleans = {false, true})
    @DisplayName("the timer thread is refused, waiting or not")
    void timerThread(boolean waits) throws InterruptedException {
      assertThat((Throwable) guard("redisson-timer-3-1", waits))
          .isExactlyInstanceOf(IllegalStateException.class)
          .hasMessageStartingWith("Locksmith cannot run on Redisson's timer thread");
    }

    @Test
    @DisplayName("an executor thread is refused only for an acquire that waits")
    void executorThread() throws InterruptedException {
      assertThat(guard("redisson-4-1", false)).isEqualTo("allowed");
      assertThat((Throwable) guard("redisson-4-1", true))
          .isExactlyInstanceOf(IllegalStateException.class)
          .hasMessageStartingWith(
              "Locksmith cannot wait for a lock or permit on Redisson thread [redisson-4-1]");
    }

    @ParameterizedTest(name = "waits={0}")
    @ValueSource(booleans = {false, true})
    @DisplayName("any other thread is allowed")
    void otherThread(boolean waits) throws InterruptedException {
      assertThat(guard("http-nio-8080-exec-1", waits)).isEqualTo("allowed");
    }
  }

  @Nested
  @DisplayName("onRedissonIoOrTimerThread")
  class OnRedissonIoOrTimerThread {

    @Test
    @DisplayName("true on an I/O or the timer thread, false on an executor or any other thread")
    void classifies() throws InterruptedException {
      Supplier<?> check = RedissonFutures::onRedissonIoOrTimerThread;
      assertThat(onThread("redisson-netty-2-1", check)).isEqualTo(true);
      assertThat(onThread("redisson-timer-3-1", check)).isEqualTo(true);
      assertThat(onThread("redisson-4-1", check)).isEqualTo(false);
      assertThat(onThread("main", check)).isEqualTo(false);
    }
  }

  @Nested
  @DisplayName("requireClusterSafeKey")
  class RequireClusterSafeKey {

    private RedissonClient client(boolean cluster) {
      Config config = new Config();
      if (cluster) {
        config.useClusterServers();
      } else {
        config.useSingleServer();
      }
      RedissonClient redisson = mock(RedissonClient.class);
      when(redisson.getConfig()).thenReturn(config);
      return redisson;
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(
        strings = {
          "p:order:x{1",
          "p:order:x{}1",
          "p:order:x{",
          "p:{x",
          "p:a{}b}",
          "p:order:42}",
          "p:}",
          "p:a}b"
        })
    @DisplayName("on Cluster, a '{' that forms no hash tag, or a '}' without '{', is refused")
    void refusedOnCluster(String key) {
      assertThatThrownBy(() -> RedissonFutures.requireClusterSafeKey(client(true), key))
          .isExactlyInstanceOf(LocksmithConfigurationException.class)
          .hasMessageStartingWith(
              "Key [" + key + "] contains a '{' or '}' that forms no Redis Cluster hash tag");
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"p:order:{x}1", "p:order:{42}", "p:a}{b}", "p:order:42"})
    @DisplayName("on Cluster, a key with a proper hash tag, or without braces, is allowed")
    void allowedOnCluster(String key) {
      assertThatNoException()
          .isThrownBy(() -> RedissonFutures.requireClusterSafeKey(client(true), key));
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"p:order:x{1", "p:order:42}"})
    @DisplayName("outside Cluster, a brace that forms no hash tag is allowed")
    void allowedOutsideCluster(String key) {
      assertThatNoException()
          .isThrownBy(() -> RedissonFutures.requireClusterSafeKey(client(false), key));
    }

    @Test
    @DisplayName("a key without braces never reads the client's configuration")
    void noBraceSkipsConfig() {
      RedissonClient redisson = mock(RedissonClient.class);

      RedissonFutures.requireClusterSafeKey(redisson, "p:order:42");

      verifyNoInteractions(redisson);
    }
  }
}
