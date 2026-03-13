package com.janeluo.luban.rds.autoconfigure;

import com.janeluo.luban.rds.client.NettyRedisClient;
import com.janeluo.luban.rds.client.RedisClient;
import com.janeluo.luban.rds.server.EmbeddedRedisServer;
import com.janeluo.luban.rds.server.NettyRedisServer;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration;
import org.springframework.boot.autoconfigure.transaction.TransactionAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.autoconfigure.amqp.RabbitAutoConfiguration;
import org.springframework.boot.autoconfigure.batch.BatchAutoConfiguration;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * Luban RDS 自动配置类 - Bootstrap 级别
 * 
 * <p>自动配置 Luban RDS 服务器和客户端 Bean。
 * 通过 {@link LubanRdsProperties} 配置服务器行为，支持内嵌模式和客户端模式。
 * 
 * <p><strong>Bootstrap 级别配置</strong>：设置为最早的启动阶段，确保在其他任何
 * 自动配置（包括 JPA、DataSource、MyBatis、RabbitMQ 等）之前完成服务器启动和客户端连接。
 * 
 * <p>使用 before 属性确保在以下自动配置之前执行：
 * <ul>
 *   <li>DataSourceAutoConfiguration</li>
 *   <li>JpaAutoConfiguration</li>
 *   <li>MybatisAutoConfiguration</li>
 *   <li>RedisAutoConfiguration</li>
 *   <li>RabbitAutoConfiguration</li>
 *   <li>BatchAutoConfiguration</li>
 * </ul>
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
@AutoConfiguration(
    before = {
        DataSourceAutoConfiguration.class,
        JdbcTemplateAutoConfiguration.class,
        TransactionAutoConfiguration.class,
        RedisAutoConfiguration.class,
        RabbitAutoConfiguration.class,
        BatchAutoConfiguration.class,
        JacksonAutoConfiguration.class
    }
)
@EnableConfigurationProperties(LubanRdsProperties.class)
@ConditionalOnProperty(prefix = "luban.rds", name = "enabled", havingValue = "true", matchIfMissing = true)
public class LubanRdsAutoConfiguration {

    private static final Logger logger = LoggerFactory.getLogger(LubanRdsAutoConfiguration.class);

    private final LubanRdsProperties properties;
    private EmbeddedRedisServer embeddedRedisServer;

    public LubanRdsAutoConfiguration(LubanRdsProperties properties) {
        this.properties = properties;
        // 在构造函数中立即启动服务器，确保在 Spring 上下文初始化之前完成
        if (properties.isEnabled()) {
            logger.info("Initializing Luban RDS embedded server in constructor with config: host={}, port={}, bossThreads={}, workerThreads={}", 
                    properties.getHost(), properties.getPort(), properties.getBossThreads(), properties.getWorkerThreads());
            
            this.embeddedRedisServer = new EmbeddedRedisServer(properties.getPort());
            this.embeddedRedisServer.start();
            
            logger.info("Luban RDS embedded server started successfully on port {}", this.embeddedRedisServer.getPort());
        }
    }

    /**
     * 配置并启动内嵌 Redis 服务器
     * 
     * <p>服务器已在构造函数中启动，此处仅将实例注册为 Bean。
     * 这样确保服务器在 Spring 上下文初始化的最早期阶段就已经运行。
     * 
     * <p>仅当 {@code luban.rds.enabled=true} 时生效。
     * 服务器使用配置的端口和主机启动，支持随机端口分配（port=0）。
     * 
     * @return 内嵌 Redis 服务器实例
     */
    @Bean(destroyMethod = "stop")
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "luban.rds", name = "enabled", havingValue = "true")
    public EmbeddedRedisServer embeddedRedisServer() {
        return this.embeddedRedisServer;
    }

    /**
     * 应用关闭时的清理方法
     */
    @PreDestroy
    public void cleanup() {
        if (embeddedRedisServer != null && embeddedRedisServer.isRunning()) {
            logger.info("Shutting down Luban RDS embedded server");
            embeddedRedisServer.stop();
        }
    }

    /**
     * 配置 Redis 客户端
     * 
     * <p>仅当 {@code luban.rds.enabled=true} 时生效。
     * 客户端自动连接到内嵌服务器或远程服务器。
     * 
     * <p>通过 @DependsOn 注解确保服务器先于客户端启动。
     * 添加重试逻辑确保连接成功。
     * 
     * @return Redis 客户端实例
     */
    @org.springframework.context.annotation.DependsOn("embeddedRedisServer")
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "luban.rds", name = "enabled", havingValue = "true")
    public RedisClient redisClient(EmbeddedRedisServer embeddedRedisServer) {
        int port = embeddedRedisServer.getPort();
        String host = properties.getHost();
        
        logger.info("Initializing Luban RDS client connecting to {}:{}", host, port);
        
        // 等待服务器启动完成
        waitForServerReady(embeddedRedisServer);
        
        // 创建并连接客户端
        RedisClient client = new NettyRedisClient(host, port);
        
        // 重试连接以确保成功
        connectWithRetry(client, host, port);
        
        logger.info("Luban RDS client connected successfully to {}:{}", host, port);
        return client;
    }

    /**
     * 等待服务器启动完成
     * 
     * @param server 服务器实例
     */
    private void waitForServerReady(EmbeddedRedisServer server) {
        int maxRetries = 30; // 最多等待 30 次（每次 100ms）
        int retryCount = 0;
        
        while (!server.isRunning() && retryCount < maxRetries) {
            try {
                Thread.sleep(100);
                retryCount++;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while waiting for server to start", e);
            }
        }
        
        if (!server.isRunning()) {
            throw new IllegalStateException("Luban RDS server failed to start within " + (maxRetries * 100) + "ms");
        }
        
        logger.debug("Server ready check passed after {} retries", retryCount);
    }

    /**
     * 带重试的连接方法
     * 
     * @param client 客户端实例
     * @param host 主机地址
     * @param port 端口号
     */
    private void connectWithRetry(RedisClient client, String host, int port) {
        int maxRetries = 5;
        int retryDelay = 500; // 毫秒
        int retryCount = 0;
        
        while (retryCount < maxRetries) {
            try {
                client.connect();
                // 简单的连接验证
                if (!client.isConnected()) {
                    throw new IllegalStateException("Client not connected after connect() call");
                }
                break;
            } catch (Exception e) {
                retryCount++;
                if (retryCount >= maxRetries) {
                    throw new IllegalStateException("Failed to connect to Luban RDS server after " + maxRetries + " attempts", e);
                }
                logger.warn("Connection attempt {} failed, retrying in {}ms...", retryCount, retryDelay);
                try {
                    Thread.sleep(retryDelay);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Interrupted during connection retry", ie);
                }
            }
        }
    }

}

