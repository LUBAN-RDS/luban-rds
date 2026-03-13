package com.janeluo.luban.rds.autoconfigure;

import com.janeluo.luban.rds.client.NettyRedisClient;
import com.janeluo.luban.rds.client.RedisClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.DependsOn;

/**
 * Luban RDS 客户端自动配置类
 * 
 * <p>仅配置客户端 Bean，不启动内嵌服务器。
 * 适用于连接远程 Luban RDS 服务器的场景。
 * 
 * <p>添加连接重试机制确保远程连接成功。
 * 
 * <p>配置示例：
 * <pre>
 * luban.rds.client.enabled=true
 * luban.rds.client.host=remote-server
 * luban.rds.client.port=9736
 * </pre>
 * 
 * @author janeluo
 * @since 1.0.0
 */
@AutoConfiguration(after = LubanRdsAutoConfiguration.class)
@EnableConfigurationProperties(LubanRdsProperties.class)
@ConditionalOnProperty(prefix = "luban.rds.client", name = "enabled", havingValue = "true")
public class LubanRdsClientAutoConfiguration {

    private static final Logger logger = LoggerFactory.getLogger(LubanRdsClientAutoConfiguration.class);

    private final LubanRdsProperties properties;

    public LubanRdsClientAutoConfiguration(LubanRdsProperties properties) {
        this.properties = properties;
    }

    /**
     * 配置并创建远程 Redis 客户端
     * 
     * <p>仅当 {@code luban.rds.client.enabled=true} 时生效。
     * 客户端连接到配置的远程服务器。
     * 
     * <p>添加重试机制确保连接成功。
     * 
     * @return Redis 客户端实例
     */
    @Bean
    @ConditionalOnMissingBean
    public RedisClient remoteRedisClient() {
        String host = properties.getHost();
        int port = properties.getPort();
        
        logger.info("Initializing remote Luban RDS client connecting to {}:{}", host, port);
        
        RedisClient client = new NettyRedisClient(host, port);
        
        // 带重试的连接
        connectWithRetry(client, host, port);
        
        logger.info("Remote Luban RDS client connected successfully to {}:{}", host, port);
        return client;
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
        int retryDelay = 1000; // 毫秒
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
                    throw new IllegalStateException("Failed to connect to remote Luban RDS server after " + maxRetries + " attempts", e);
                }
                logger.warn("Connection attempt {} failed, retrying in {}ms... Error: {}", 
                        retryCount, retryDelay, e.getMessage());
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
