package com.janeluo.luban.rds.autoconfigure;

import com.janeluo.luban.rds.client.NettyRedisClient;
import com.janeluo.luban.rds.client.RedisClient;
import com.janeluo.luban.rds.server.EmbeddedRedisServer;
import com.janeluo.luban.rds.server.NettyRedisServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * Luban RDS 自动配置类
 * 
 * <p>自动配置 Luban RDS 服务器和客户端 Bean。
 * 通过 {@link LubanRdsProperties} 配置服务器行为，支持内嵌模式和客户端模式。
 * 
 * <p>配置示例：
 * <pre>
 * luban.rds.enabled=true
 * luban.rds.port=9736
 * luban.rds.host=localhost
 * </pre>
 * 
 * @author janeluo
 * @since 1.0.0
 */
@AutoConfiguration
@EnableConfigurationProperties(LubanRdsProperties.class)
@ConditionalOnProperty(prefix = "luban.rds", name = "enabled", havingValue = "true", matchIfMissing = true)
public class LubanRdsAutoConfiguration {

    private static final Logger logger = LoggerFactory.getLogger(LubanRdsAutoConfiguration.class);

    private final LubanRdsProperties properties;

    public LubanRdsAutoConfiguration(LubanRdsProperties properties) {
        this.properties = properties;
    }

    /**
     * 配置并启动内嵌 Redis 服务器
     * 
     * <p>仅当 {@code luban.rds.enabled=true} 时生效。
     * 服务器使用配置的端口和主机启动，支持随机端口分配（port=0）。
     * 
     * @return 内嵌 Redis 服务器实例
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "luban.rds", name = "enabled", havingValue = "true")
    public EmbeddedRedisServer embeddedRedisServer() {
        logger.info("Initializing Luban RDS embedded server with config: host={}, port={}, bossThreads={}, workerThreads={}", 
                properties.getHost(), properties.getPort(), properties.getBossThreads(), properties.getWorkerThreads());
        
        EmbeddedRedisServer server = new EmbeddedRedisServer(properties.getPort());
        server.start();
        
        logger.info("Luban RDS embedded server started successfully on port {}", server.getPort());
        return server;
    }

    /**
     * 配置 Redis 客户端
     * 
     * <p>仅当 {@code luban.rds.client.enabled=true} 时生效。
     * 客户端自动连接到内嵌服务器或远程服务器。
     * 
     * @return Redis 客户端实例
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "luban.rds", name = "enabled", havingValue = "true")
    public RedisClient redisClient(EmbeddedRedisServer embeddedRedisServer) {
        int port = embeddedRedisServer.getPort();
        String host = properties.getHost();
        
        logger.info("Initializing Luban RDS client connecting to {}:{}", host, port);
        
        RedisClient client = new NettyRedisClient(host, port);
        client.connect();
        
        logger.info("Luban RDS client connected successfully to {}:{}", host, port);
        return client;
    }

}
