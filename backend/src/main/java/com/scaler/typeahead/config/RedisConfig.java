package com.scaler.typeahead.config;

import com.scaler.typeahead.cache.CacheNode;
import com.scaler.typeahead.cache.ConsistentHashRouter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds the distributed-cache topology from the {@code cache.nodes} property
 * and assembles the consistent-hashing ring.
 *
 * <p>Each configured endpoint ({@code host:port:db}) gets its OWN
 * {@link LettuceConnectionFactory} and {@link StringRedisTemplate}. This is what
 * makes the cache genuinely sharded - a connection per node rather than one
 * pooled client - and lets the debug endpoint query each node independently.
 *
 * <p>Separately, the {@link Primary} connection factory points at
 * {@code spring.data.redis.*} and backs the Redis-Streams batch-write pipeline.
 */
@Configuration
public class RedisConfig {

    @Value("${cache.nodes}")
    private String cacheNodesCsv;

    @Value("${cache.virtual-nodes:150}")
    private int virtualNodes;

    @Value("${spring.data.redis.host:localhost}")
    private String streamsHost;

    @Value("${spring.data.redis.port:6379}")
    private int streamsPort;

    // ---- Streams Redis (batch writes) -------------------------------------

    /** Primary factory used by Spring Data for the Streams pipeline. */
    @Bean
    @Primary
    public RedisConnectionFactory streamsConnectionFactory() {
        LettuceConnectionFactory f =
                new LettuceConnectionFactory(new RedisStandaloneConfiguration(streamsHost, streamsPort));
        f.afterPropertiesSet();
        return f;
    }

    @Bean
    @Primary
    public StringRedisTemplate streamsRedisTemplate(RedisConnectionFactory streamsConnectionFactory) {
        return new StringRedisTemplate(streamsConnectionFactory);
    }

    /**
     * DEDICATED template/connection for the consumer's BLOCKING {@code XREADGROUP}.
     * Lettuce multiplexes commands over a shared connection, so a blocking read
     * on the primary template would stall {@code XADD}/{@code ZINCRBY} on the
     * search hot path. Isolating it on its own connection prevents that.
     */
    @Bean
    public StringRedisTemplate consumerRedisTemplate() {
        LettuceConnectionFactory f =
                new LettuceConnectionFactory(new RedisStandaloneConfiguration(streamsHost, streamsPort));
        f.afterPropertiesSet();
        StringRedisTemplate t = new StringRedisTemplate(f);
        t.afterPropertiesSet();
        return t;
    }

    // ---- Distributed cache ring -------------------------------------------

    /**
     * Parses {@code cache.nodes} and builds one {@link CacheNode} per endpoint.
     * Each node owns an independent connection factory + template.
     */
    @Bean
    public List<CacheNode> cacheNodes() {
        List<CacheNode> nodes = new ArrayList<>();
        String[] specs = cacheNodesCsv.split(",");
        for (int i = 0; i < specs.length; i++) {
            String spec = specs[i].trim();
            String[] parts = spec.split(":");
            String host = parts[0];
            int port = Integer.parseInt(parts[1]);
            int db = parts.length > 2 ? Integer.parseInt(parts[2]) : 0;

            RedisStandaloneConfiguration cfg = new RedisStandaloneConfiguration(host, port);
            cfg.setDatabase(db);
            LettuceConnectionFactory factory = new LettuceConnectionFactory(cfg);
            factory.afterPropertiesSet();

            StringRedisTemplate template = new StringRedisTemplate(factory);
            template.afterPropertiesSet();

            String id = "node-" + i + " (" + host + ":" + port + "/" + db + ")";
            nodes.add(new CacheNode(id, template));
        }
        return nodes;
    }

    @Bean
    public ConsistentHashRouter consistentHashRouter() {
        // Call the @Bean method directly: in a @Configuration class this is
        // intercepted by CGLIB and returns the SAME singleton list, so there is
        // no ambiguity about injecting a List<CacheNode>.
        return new ConsistentHashRouter(cacheNodes(), virtualNodes);
    }
}
