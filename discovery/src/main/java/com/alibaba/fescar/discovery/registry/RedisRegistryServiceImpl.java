/*
 *  Copyright 1999-2018 Alibaba Group Holding Ltd.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package com.alibaba.fescar.discovery.registry;

import com.alibaba.fescar.common.exception.ShouldNeverHappenException;
import com.alibaba.fescar.common.thread.NamedThreadFactory;
import com.alibaba.fescar.common.util.NetUtil;
import com.alibaba.fescar.common.util.StringUtils;
import com.alibaba.fescar.config.Configuration;
import com.alibaba.fescar.config.ConfigurationFactory;
import com.google.common.collect.Lists;
import org.apache.commons.pool2.impl.GenericObjectPoolConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPubSub;
import redis.clients.jedis.Protocol;

import java.lang.management.ManagementFactory;
import java.net.InetSocketAddress;
import java.util.*;
import java.util.concurrent.*;

/**
 *  The type Redis registry service.
 *
 *  @author: kl @kailing.pub
 *  @date: 2019/2/27
 */
public class RedisRegistryServiceImpl implements RegistryService<RedisListener> {

    private static final Logger LOGGER = LoggerFactory.getLogger(RedisRegistryServiceImpl.class);
    private static final String PRO_SERVER_ADDR_KEY = "serverAddr";
    private static final String REDIS_FILEKEY_PREFIX = "registry.redis.";
    private static final String DEFAULT_CLUSTER = "default";
    private static final String REGISTRY_CLUSTER_KEY = "cluster";
    private String clusterName;
    private static final String REDIS_DB = "db";
    private static final String REDIS_PASSWORD = "password";
    private static final ConcurrentMap<String, List<RedisListener>> LISTENER_SERVICE_MAP = new ConcurrentHashMap<>();
    private static final ConcurrentMap<String, Set<InetSocketAddress>> CLUSTER_ADDRESS_MAP = new ConcurrentHashMap<>();
    private static volatile RedisRegistryServiceImpl instance;
    private static volatile JedisPool jedisPool;
    private ExecutorService threadPoolExecutor = new ScheduledThreadPoolExecutor(1,
            new NamedThreadFactory("RedisRegistryService", 1));
    private RedisRegistryServiceImpl() {
        Configuration fescarConfig = ConfigurationFactory.getInstance();
        this.clusterName = fescarConfig.getConfig(REDIS_FILEKEY_PREFIX + REGISTRY_CLUSTER_KEY, DEFAULT_CLUSTER);
        String password = fescarConfig.getConfig(getRedisPasswordFileKey());
        String serverAddr = fescarConfig.getConfig(getRedisAddrFileKey());
        String[] serverArr = serverAddr.split(":");
        String host = serverArr[0];
        int port = Integer.valueOf(serverArr[1]);
        int db = fescarConfig.getInt(getRedisDbFileKey());
        GenericObjectPoolConfig redisConfig = new GenericObjectPoolConfig();
        redisConfig.setTestOnBorrow(fescarConfig.getBoolean(REDIS_FILEKEY_PREFIX + "test.on.borrow", true));
        redisConfig.setTestOnReturn(fescarConfig.getBoolean(REDIS_FILEKEY_PREFIX + "test.on.return", false));
        redisConfig.setTestWhileIdle(fescarConfig.getBoolean(REDIS_FILEKEY_PREFIX + "test.while.idle", false));
        int maxIdle = fescarConfig.getInt(REDIS_FILEKEY_PREFIX + "max.idle", 0);
        if (maxIdle > 0) {
            redisConfig.setMaxIdle(maxIdle);
        }
        int minIdle = fescarConfig.getInt(REDIS_FILEKEY_PREFIX + "min.idle", 0);
        if (minIdle > 0) {
            redisConfig.setMinIdle(minIdle);
        }
        int maxActive = fescarConfig.getInt(REDIS_FILEKEY_PREFIX + "max.active", 0);
        if (maxActive > 0) {
            redisConfig.setMaxTotal(maxActive);
        }
        int maxTotal = fescarConfig.getInt(REDIS_FILEKEY_PREFIX + "max.total", 0);
        if (maxTotal > 0) {
            redisConfig.setMaxTotal(maxTotal);
        }
        int maxWait = fescarConfig.getInt(REDIS_FILEKEY_PREFIX + "max.wait", fescarConfig.getInt(REDIS_FILEKEY_PREFIX + "timeout", 0));
        if (maxWait > 0) {
            redisConfig.setMaxWaitMillis(maxWait);
        }
        int numTestsPerEvictionRun = fescarConfig.getInt(REDIS_FILEKEY_PREFIX + "num.tests.per.eviction.run", 0);
        if (numTestsPerEvictionRun > 0) {
            redisConfig.setNumTestsPerEvictionRun(numTestsPerEvictionRun);
        }
        int timeBetweenEvictionRunsMillis = fescarConfig.getInt(REDIS_FILEKEY_PREFIX + "time.between.eviction.runs.millis", 0);
        if (timeBetweenEvictionRunsMillis > 0) {
            redisConfig.setTimeBetweenEvictionRunsMillis(timeBetweenEvictionRunsMillis);
        }
        int minEvictableIdleTimeMillis = fescarConfig.getInt(REDIS_FILEKEY_PREFIX + "min.evictable.idle.time.millis", 0);
        if (minEvictableIdleTimeMillis > 0) {
            redisConfig.setMinEvictableIdleTimeMillis(minEvictableIdleTimeMillis);
        }
        if (StringUtils.isEmpty(password)) {
            jedisPool = new JedisPool(redisConfig, host, port, Protocol.DEFAULT_TIMEOUT, null, db);
        } else {
            jedisPool = new JedisPool(redisConfig, host, port, Protocol.DEFAULT_TIMEOUT, password, db);
        }
    }

    public static RedisRegistryServiceImpl getInstance() {
        if (null == instance) {
            synchronized (RedisRegistryServiceImpl.class) {
                if (null == instance) {
                    instance = new RedisRegistryServiceImpl();
                }
            }
        }
        return instance;
    }

    @Override
    public void register(InetSocketAddress address) {
        NetUtil.validAddress(address);
        String serverAddr = NetUtil.toStringAddress(address);
        Jedis jedis = jedisPool.getResource();
        try {
            jedis.hset(getRedisRegistryKey(), serverAddr, ManagementFactory.getRuntimeMXBean().getName());
            jedis.publish(getRedisRegistryKey(), serverAddr + "-" + RedisListener.REGISTER);
        }finally {
            jedis.close();
        }
    }
    @Override
    public void unregister(InetSocketAddress address) {
        NetUtil.validAddress(address);
        String serverAddr = NetUtil.toStringAddress(address);
        Jedis jedis = jedisPool.getResource();
        try {
            jedis.hdel(getRedisRegistryKey(), serverAddr);
            jedis.publish(getRedisRegistryKey(), serverAddr + "-" + RedisListener.UN_REGISTER);
        }finally {
            jedis.close();
        }
    }

    @Override
    public void subscribe(String cluster, RedisListener listener) {
        String redisRegistryKey = REDIS_FILEKEY_PREFIX + cluster;
        LISTENER_SERVICE_MAP.putIfAbsent(cluster, new ArrayList<>());
        LISTENER_SERVICE_MAP.get(cluster).add(listener);
        threadPoolExecutor.submit(new Runnable() {
            @Override
            public void run() {
                try {
                    Jedis jedis = jedisPool.getResource();
                    try {
                        jedis.subscribe(new NotifySub(LISTENER_SERVICE_MAP.get(cluster)), redisRegistryKey);
                    }finally {
                        jedis.close();
                    }
                }catch (Exception e){
                    LOGGER.error(e.getMessage(),e);
                }
            }
        });
    }

    @Override
    public void unsubscribe(String cluster, RedisListener listener) {
    }

    @Override
    public List<InetSocketAddress> lookup(String key) {
        Configuration config = ConfigurationFactory.getInstance();
        String clusterName = config.getConfig(PREFIX_SERVICE_ROOT + CONFIG_SPLIT_CHAR + PREFIX_SERVICE_MAPPING + key);
        if (null == clusterName) {
            return null;
        }
        if (!LISTENER_SERVICE_MAP.containsKey(clusterName)) {
            Jedis jedis = jedisPool.getResource();
            Map<String, String> instances = null;
            try {
                instances = jedis.hgetAll(getRedisRegistryKey());
            }finally {
                jedis.close();
            }
            if (null != instances) {
                Set<InetSocketAddress> newAddressList = new HashSet<>();
                for (Map.Entry<String, String> instance : instances.entrySet()) {
                    String serverAddr = instance.getKey();
                    newAddressList.add(NetUtil.toInetSocketAddress(serverAddr));
                }
                CLUSTER_ADDRESS_MAP.put(clusterName, newAddressList);
            }
            subscribe(clusterName, new RedisListener() {
                @Override
                public void onEvent(String msg) {
                    String[] msgr = msg.split("-");
                    String serverAddr =msgr[0];
                    String eventType = msgr[1];
                    switch (eventType) {
                        case RedisListener.REGISTER:
                            CLUSTER_ADDRESS_MAP.get(clusterName).add(NetUtil.toInetSocketAddress(serverAddr));
                            break;
                        case RedisListener.UN_REGISTER:
                            CLUSTER_ADDRESS_MAP.get(clusterName).remove(NetUtil.toInetSocketAddress(serverAddr));
                            break;
                        default:
                            throw new ShouldNeverHappenException("unknow redis msg:" + msg);
                    }
                }
            });
        }
        return Lists.newArrayList(CLUSTER_ADDRESS_MAP.get(clusterName));
    }

    private class NotifySub extends JedisPubSub {

        private final List<RedisListener> redisListeners;

        NotifySub(List<RedisListener> redisListeners) {
            this.redisListeners = redisListeners;
        }

        @Override
        public void onMessage(String key, String msg) {
            for (RedisListener listener : redisListeners) {
                listener.onEvent(msg);
            }
        }
    }

    private String getRedisRegistryKey() {
        return REDIS_FILEKEY_PREFIX + clusterName;
    }

    private String getRedisAddrFileKey() {
        return REDIS_FILEKEY_PREFIX + PRO_SERVER_ADDR_KEY;
    }

    private String getRedisPasswordFileKey() {
        return REDIS_FILEKEY_PREFIX + REDIS_PASSWORD;
    }

    private String getRedisDbFileKey() {
        return REDIS_FILEKEY_PREFIX + REDIS_DB;
    }
}