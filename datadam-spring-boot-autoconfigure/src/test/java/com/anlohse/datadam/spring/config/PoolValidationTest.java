package com.anlohse.datadam.spring.config;

import org.apache.commons.pool2.impl.DefaultPooledObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.*;
import redis.clients.jedis.*;
import redis.clients.jedis.exceptions.JedisDataException;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(OutputCaptureExtension.class)
class PoolValidationTest {
    @Test void validationDoesNotLogNativeErrorDetails(CapturedOutput output) {
        var factory = new DatadamJedisFactory(new HostAndPort("localhost", 6379), DefaultJedisClientConfig.builder().build());
        var jedis = mock(Jedis.class); when(jedis.isConnected()).thenReturn(true);
        when(jedis.ping()).thenThrow(new JedisDataException("NOPERM private-user private-value"));
        assertThat(factory.validateObject(new DefaultPooledObject<>(jedis))).isFalse();
        assertThat(output.getAll()).doesNotContain("private-user", "private-value");
        doReturn("PONG").when(jedis).ping(); assertThat(factory.validateObject(new DefaultPooledObject<>(jedis))).isTrue();
    }
}
