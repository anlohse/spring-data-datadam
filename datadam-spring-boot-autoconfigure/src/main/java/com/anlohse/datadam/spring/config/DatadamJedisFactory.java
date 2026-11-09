package com.anlohse.datadam.spring.config;

import org.apache.commons.pool2.PooledObject;
import redis.clients.jedis.*;

/** Static-endpoint validation without JedisFactory's WARN exception logging of server details. */
final class DatadamJedisFactory extends JedisFactory {
    DatadamJedisFactory(HostAndPort address, JedisClientConfig config) { super(address, config); }
    @Override public boolean validateObject(PooledObject<Jedis> object) {
        try { return object.getObject().isConnected() && "PONG".equals(object.getObject().ping()); }
        catch (RuntimeException e) { return false; }
    }
}
