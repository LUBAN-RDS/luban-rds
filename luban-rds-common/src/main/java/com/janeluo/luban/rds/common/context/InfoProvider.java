package com.janeluo.luban.rds.common.context;

import java.util.Map;

/**
 * 信息提供者接口，用于获取服务器的各种运行时信息
 */
public interface InfoProvider {
    /**
     * 获取指定板块的信息
     * @param section 板块名称，如 "server", "clients", "memory", "persistence", "stats", "replication", "cpu", "commandstats", "cluster", "keyspace"
     * @return 包含该板块信息的Map
     */
    Map<String, Object> getInfo(String section);
}
