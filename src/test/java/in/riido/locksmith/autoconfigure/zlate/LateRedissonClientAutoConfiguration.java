package in.riido.locksmith.autoconfigure.zlate;

import static org.mockito.Mockito.mock;

import org.redisson.api.RedissonClient;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * Defines a {@code RedissonClient} bean from an auto-configuration whose class name sorts after
 * Locksmith's, as one in an {@code io.}, {@code net.} or {@code org.} package does.
 */
@AutoConfiguration
public class LateRedissonClientAutoConfiguration {

  @Bean
  RedissonClient redissonClient() {
    return mock(RedissonClient.class);
  }
}
