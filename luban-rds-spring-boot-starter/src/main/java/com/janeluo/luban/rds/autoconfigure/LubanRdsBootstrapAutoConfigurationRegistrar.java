package com.janeluo.luban.rds.autoconfigure;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurationImportFilter;
import org.springframework.boot.autoconfigure.AutoConfigurationImportSelector;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Luban RDS Bootstrap 级别自动配置选择器
 * 
 * <p>确保 Luban RDS 自动配置在最早的阶段加载，优先级高于其他所有自动配置。
 * 
 * @author janeluo
 * @since 1.0.0
 */
public class LubanRdsBootstrapAutoConfigurationRegistrar {

    /**
     * 注册 Luban RDS 自动配置，确保最早加载
     * 
     * @return 自动配置类列表
     */
    public static String[] selectImports() {
        return new String[] {
            LubanRdsAutoConfiguration.class.getName(),
            LubanRdsClientAutoConfiguration.class.getName()
        };
    }
}
