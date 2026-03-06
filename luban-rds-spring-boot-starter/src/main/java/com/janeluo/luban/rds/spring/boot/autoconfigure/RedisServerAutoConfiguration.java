package com.janeluo.luban.rds.spring.boot.autoconfigure;

import com.janeluo.luban.rds.server.EmbeddedRedisServer;
import com.janeluo.luban.rds.server.RedisServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.annotation.PreDestroy;

/**
 * Redis服务器自动配置类
 * 
 * <p>Spring Boot自动配置类，用于创建和管理内嵌Redis服务器实例。
 * 当配置属性 spring.redis.embedded.enabled=true 时自动激活。
 * 
 * @author janeluo
 * @since 1.0.0
 */
@Configuration
@EnableConfigurationProperties(RedisServerProperties.class)
@ConditionalOnProperty(name = "spring.redis.embedded.enabled", havingValue = "true")
public class RedisServerAutoConfiguration {
    
    private static final Logger logger = LoggerFactory.getLogger(RedisServerAutoConfiguration.class);
    
    private RedisServer redisServer;
    
    @Autowired
    private RedisServerProperties properties;
    
    @Bean(destroyMethod = "stop")
    public RedisServer redisServer() {
        logger.info("Creating embedded LbRDS server with port: {}", properties.getPort());
        redisServer = new EmbeddedRedisServer(properties.getPort());
        redisServer.start();
        logger.info("Embedded LbRDS server started on port: {}", redisServer.getPort());
        return redisServer;
    }
    
    @PreDestroy
    public void destroy() {
        if (redisServer != null && redisServer.isRunning()) {
            logger.info("Stopping embedded LbRDS server");
            redisServer.stop();
        }
    }
}
