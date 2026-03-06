package com.janeluo.luban.rds.spring.boot.autoconfigure;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Redis服务器配置属性
 * 
 * <p>用于配置内嵌Redis服务器的属性，包括：
 * <ul>
 *   <li>enabled - 是否启用内嵌服务器</li>
 *   <li>port - 服务器端口</li>
 *   <li>host - 服务器主机地址</li>
 * </ul>
 * 
 * @author janeluo
 * @since 1.0.0
 */
@ConfigurationProperties(prefix = "spring.redis.embedded")
public class RedisServerProperties {
    
    /**
     * 是否启用内嵌Redis服务器，默认为false
     */
    private boolean enabled = false;
    
    /**
     * Redis服务器端口，默认为9736
     */
    private int port = 9736;
    
    /**
     * Redis服务器主机地址，默认为localhost
     */
    private String host = "localhost";
    
    public boolean isEnabled() {
        return enabled;
    }
    
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }
    
    public int getPort() {
        return port;
    }
    
    public void setPort(int port) {
        this.port = port;
    }
    
    public String getHost() {
        return host;
    }
    
    public void setHost(String host) {
        this.host = host;
    }
}
