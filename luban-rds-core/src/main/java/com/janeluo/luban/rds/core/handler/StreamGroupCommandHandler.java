package com.janeluo.luban.rds.core.handler;

import com.google.common.collect.Sets;
import com.janeluo.luban.rds.common.constant.RdsCommandConstant;
import com.janeluo.luban.rds.core.stream.BlockingResult;
import com.janeluo.luban.rds.core.stream.StreamEntry;
import com.janeluo.luban.rds.core.stream.StreamId;
import com.janeluo.luban.rds.core.store.MemoryStore;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Stream 消费者组命令处理器
 * 
 * <p>负责处理 Redis Stream 消费者组相关的所有命令，包括：
 * <ul>
 *   <li>XGROUP - 消费者组管理（CREATE/DESTROY/DELCONSUMER/SETID）</li>
 *   <li>XREADGROUP - 消费者组读取消息</li>
 *   <li>XACK - 确认消息</li>
 *   <li>XPENDING - 查询待处理消息</li>
 *   <li>XCLAIM - 转移消息所有权</li>
 *   <li>XAUTOCLAIM - 自动转移超时消息</li>
 * </ul>
 * 
 * @author janeluo
 * @since 1.0.0
 */
public class StreamGroupCommandHandler implements CommandHandler {

    private final Set<String> supportedCommands = Sets.newHashSet(
        RdsCommandConstant.XGROUP,
        RdsCommandConstant.XREADGROUP,
        RdsCommandConstant.XACK,
        RdsCommandConstant.XPENDING,
        RdsCommandConstant.XCLAIM,
        RdsCommandConstant.XAUTOCLAIM
    );

    @Override
    public Object handle(int database, String[] args, MemoryStore store) {
        String command = args[0].toUpperCase();

        switch (command) {
            case RdsCommandConstant.XGROUP:
                return handleXGroup(database, args, store);
            case RdsCommandConstant.XREADGROUP:
                return handleXReadGroup(database, args, store);
            case RdsCommandConstant.XACK:
                return handleXAck(database, args, store);
            case RdsCommandConstant.XPENDING:
                return handleXPending(database, args, store);
            case RdsCommandConstant.XCLAIM:
                return handleXClaim(database, args, store);
            case RdsCommandConstant.XAUTOCLAIM:
                return handleXAutoClaim(database, args, store);
            default:
                return "-ERR unknown command\r\n";
        }
    }

    // ==================== XGROUP 命令处理 ====================

    /**
     * 处理 XGROUP 命令
     * 格式: XGROUP CREATE key group ID [MKSTREAM]
     *       XGROUP DESTROY key group
     *       XGROUP DELCONSUMER key group consumer
     *       XGROUP SETID key group ID
     */
    private Object handleXGroup(int database, String[] args, MemoryStore store) {
        if (args.length < 4) {
            return "-ERR wrong number of arguments for 'xgroup' command\r\n";
        }

        String subCommand = args[1].toUpperCase();
        
        switch (subCommand) {
            case "CREATE":
                return handleXGroupCreate(database, args, store);
            case "DESTROY":
                return handleXGroupDestroy(database, args, store);
            case "DELCONSUMER":
                return handleXGroupDelConsumer(database, args, store);
            case "SETID":
                return handleXGroupSetId(database, args, store);
            default:
                return "-ERR unknown subcommand '" + args[1] + "'\r\n";
        }
    }

    /**
     * XGROUP CREATE key group ID [MKSTREAM]
     */
    private Object handleXGroupCreate(int database, String[] args, MemoryStore store) {
        if (args.length < 5) {
            return "-ERR wrong number of arguments for 'xgroup|create' command\r\n";
        }

        String key = args[2];
        String group = args[3];
        String idStr = args[4];
        boolean mkstream = false;

        // 检查是否有 MKSTREAM 选项
        if (args.length > 5) {
            for (int i = 5; i < args.length; i++) {
                if ("MKSTREAM".equalsIgnoreCase(args[i])) {
                    mkstream = true;
                }
            }
        }

        // 解析 ID
        StreamId id;
        if ("$".equals(idStr)) {
            // $ 表示从最新消息开始
            id = null;
        } else if ("0".equals(idStr) || "0-0".equals(idStr)) {
            // 0 表示从开头开始
            id = StreamId.MIN_ID;
        } else {
            try {
                id = StreamId.parse(idStr);
            } catch (IllegalArgumentException e) {
                return "-ERR Invalid stream ID specified as stream group last ID\r\n";
            }
        }

        try {
            boolean success = store.xgroupCreate(database, key, group, id, mkstream);
            if (success) {
                return "+OK\r\n";
            } else {
                return "-BUSYGROUP Consumer Group name already exists\r\n";
            }
        } catch (IllegalStateException e) {
            // 流不存在且没有 MKSTREAM
            return "-ERR no such key\r\n";
        }
    }

    /**
     * XGROUP DESTROY key group
     */
    private Object handleXGroupDestroy(int database, String[] args, MemoryStore store) {
        if (args.length < 4) {
            return "-ERR wrong number of arguments for 'xgroup|destroy' command\r\n";
        }

        String key = args[2];
        String group = args[3];

        boolean success = store.xgroupDestroy(database, key, group);
        return success ? ":1\r\n" : ":0\r\n";
    }

    /**
     * XGROUP DELCONSUMER key group consumer
     */
    private Object handleXGroupDelConsumer(int database, String[] args, MemoryStore store) {
        if (args.length < 5) {
            return "-ERR wrong number of arguments for 'xgroup|delconsumer' command\r\n";
        }

        String key = args[2];
        String group = args[3];
        String consumer = args[4];

        long pendingCount = store.xgroupDelConsumer(database, key, group, consumer);
        return ":" + pendingCount + "\r\n";
    }

    /**
     * XGROUP SETID key group ID
     */
    private Object handleXGroupSetId(int database, String[] args, MemoryStore store) {
        if (args.length < 5) {
            return "-ERR wrong number of arguments for 'xgroup|setid' command\r\n";
        }

        String key = args[2];
        String group = args[3];
        String idStr = args[4];

        // 解析 ID
        StreamId id;
        if ("$".equals(idStr)) {
            id = null;
        } else {
            try {
                id = StreamId.parse(idStr);
            } catch (IllegalArgumentException e) {
                return "-ERR Invalid stream ID specified as stream group last ID\r\n";
            }
        }

        boolean success = store.xgroupSetId(database, key, group, id);
        if (success) {
            return "+OK\r\n";
        } else {
            return "-NOGROUP No such key '" + key + "' or consumer group '" + group + "'\r\n";
        }
    }

    // ==================== XREADGROUP 命令处理 ====================

    /**
     * 处理 XREADGROUP 命令
     * 格式: XREADGROUP GROUP group consumer [COUNT count] [BLOCK milliseconds] [NOACK] STREAMS key [key ...] ID [ID ...]
     * 
     * <p>当指定 BLOCK 选项且没有新消息时，返回 BlockingResult 对象，
     * 由 RedisServerHandler 处理阻塞逻辑。
     */
    private Object handleXReadGroup(int database, String[] args, MemoryStore store) {
        if (args.length < 6) {
            return "-ERR wrong number of arguments for 'xreadgroup' command\r\n";
        }

        int index = 1;
        String group = null;
        String consumer = null;
        int count = 1;
        boolean noack = false;
        long blockTimeout = -1; // -1 表示没有 BLOCK 选项

        // 解析 GROUP 子命令
        if (!"GROUP".equalsIgnoreCase(args[index])) {
            return "-ERR syntax error, GROUP is required\r\n";
        }
        index++;
        
        if (index >= args.length) {
            return "-ERR syntax error\r\n";
        }
        group = args[index++];
        
        if (index >= args.length) {
            return "-ERR syntax error\r\n";
        }
        consumer = args[index++];

        // 解析可选参数
        while (index < args.length) {
            String option = args[index].toUpperCase();
            
            if ("COUNT".equals(option)) {
                if (index + 1 >= args.length) {
                    return "-ERR syntax error\r\n";
                }
                try {
                    count = Integer.parseInt(args[++index]);
                } catch (NumberFormatException e) {
                    return "-ERR value is not an integer or out of range\r\n";
                }
                index++;
            } else if ("BLOCK".equals(option)) {
                if (index + 1 >= args.length) {
                    return "-ERR syntax error\r\n";
                }
                try {
                    blockTimeout = Long.parseLong(args[++index]);
                } catch (NumberFormatException e) {
                    return "-ERR value is not an integer or out of range\r\n";
                }
                index++;
            } else if ("NOACK".equals(option)) {
                noack = true;
                index++;
            } else if ("STREAMS".equals(option)) {
                index++;
                break;
            } else {
                index++;
            }
        }

        // 解析 STREAMS 后的 keys 和 IDs
        if (index >= args.length) {
            return "-ERR syntax error, STREAMS is required\r\n";
        }

        int streamsIndex = index;
        // 找到 keys 和 IDs 的分界点
        int keyCount = 0;
        for (int i = streamsIndex; i < args.length; i++) {
            keyCount++;
        }
        keyCount = keyCount / 2;

        if (keyCount == 0 || streamsIndex + keyCount * 2 > args.length) {
            return "-ERR syntax error, keys and IDs count mismatch\r\n";
        }

        String[] keys = new String[keyCount];
        StreamId[] ids = new StreamId[keyCount];

        for (int i = 0; i < keyCount; i++) {
            keys[i] = args[streamsIndex + i];
            String idStr = args[streamsIndex + keyCount + i];
            
            // > 表示读取新消息
            if (">".equals(idStr)) {
                ids[i] = null;
            } else {
                try {
                    ids[i] = StreamId.parse(idStr);
                } catch (IllegalArgumentException e) {
                    return "-ERR Invalid stream ID specified\r\n";
                }
            }
        }

        // 执行读取
        List<String> resultKeys = new ArrayList<>();
        List<List<StreamEntry>> resultEntries = new ArrayList<>();

        for (int i = 0; i < keyCount; i++) {
            Map<String, List<StreamEntry>> entriesMap = store.xreadGroup(
                database, keys[i], group, consumer, ids[i], count, noack);
            
            if (entriesMap != null && !entriesMap.isEmpty()) {
                List<StreamEntry> entries = entriesMap.get(keys[i]);
                if (entries != null && !entries.isEmpty()) {
                    resultKeys.add(keys[i]);
                    resultEntries.add(entries);
                }
            }
        }

        // 如果有消息，返回结果
        if (!resultEntries.isEmpty()) {
            return buildXReadGroupResponse(resultKeys, resultEntries);
        }

        // 没有消息的情况
        if (blockTimeout >= 0) {
            // 阻塞模式：返回 BlockingResult，由 RedisServerHandler 处理阻塞
            List<String> keyList = new ArrayList<>();
            List<StreamId> idList = new ArrayList<>();
            
            for (int i = 0; i < keyCount; i++) {
                keyList.add(keys[i]);
                // 如果 ID 是 >，需要获取当前组的最后投递 ID
                StreamId startId = ids[i];
                if (startId == null) {
                    // > 表示从组的最后投递 ID 开始
                    // 这里需要从 store 获取组的最后投递 ID
                    startId = store.getGroupLastDeliveredId(database, keys[i], group);
                    if (startId == null) {
                        startId = StreamId.MIN_ID;
                    }
                }
                idList.add(startId);
            }
            
            return BlockingResult.forXReadGroup(database, keyList, idList, blockTimeout, count, group, consumer, noack);
        } else {
            // 非阻塞模式：返回空结果
            return "*0\r\n";
        }
    }

    /**
     * 构建 XREADGROUP 响应
     */
    private String buildXReadGroupResponse(List<String> keys, List<List<StreamEntry>> entriesList) {
        StringBuilder result = new StringBuilder();
        result.append("*").append(keys.size()).append("\r\n");

        for (int i = 0; i < keys.size(); i++) {
            String key = keys.get(i);
            List<StreamEntry> entries = entriesList.get(i);

            // 流条目
            result.append("*2\r\n");
            
            // 流名称
            byte[] keyBytes = key.getBytes(StandardCharsets.UTF_8);
            result.append("$").append(keyBytes.length).append("\r\n")
                  .append(key).append("\r\n");
            
            // 消息列表
            result.append("*").append(entries.size()).append("\r\n");
            for (StreamEntry entry : entries) {
                result.append(encodeStreamEntry(entry));
            }
        }

        return result.toString();
    }

    // ==================== XACK 命令处理 ====================

    /**
     * 处理 XACK 命令
     * 格式: XACK key group ID [ID ...]
     */
    private Object handleXAck(int database, String[] args, MemoryStore store) {
        if (args.length < 4) {
            return "-ERR wrong number of arguments for 'xack' command\r\n";
        }

        String key = args[1];
        String group = args[2];

        // 解析消息 IDs
        StreamId[] ids = new StreamId[args.length - 3];
        for (int i = 3; i < args.length; i++) {
            try {
                ids[i - 3] = StreamId.parse(args[i]);
            } catch (IllegalArgumentException e) {
                return "-ERR Invalid stream ID specified\r\n";
            }
        }

        long acked = store.xack(database, key, group, ids);
        return ":" + acked + "\r\n";
    }

    // ==================== XPENDING 命令处理 ====================

    /**
     * 处理 XPENDING 命令
     * 格式: XPENDING key group [start end count [consumer] [IDLE idle-time]]
     */
    private Object handleXPending(int database, String[] args, MemoryStore store) {
        if (args.length < 3) {
            return "-ERR wrong number of arguments for 'xpending' command\r\n";
        }

        String key = args[1];
        String group = args[2];

        // 检查是否有详细参数
        if (args.length == 3) {
            // 返回摘要信息
            Map<String, Object> summary = store.xpendingSummary(database, key, group);
            if (summary == null) {
                return "-NOGROUP No such key '" + key + "' or consumer group '" + group + "'\r\n";
            }
            return encodePendingSummary(summary);
        }

        // 解析详细参数
        if (args.length < 6) {
            return "-ERR wrong number of arguments for 'xpending' command\r\n";
        }

        StreamId start;
        StreamId end;
        int count;
        String consumer = null;
        long minIdleTime = 0;

        try {
            start = StreamId.parse(args[3]);
            end = StreamId.parse(args[4]);
            count = Integer.parseInt(args[5]);
        } catch (IllegalArgumentException e) {
            return "-ERR Invalid stream ID or count specified\r\n";
        }

        // 解析可选参数
        for (int i = 6; i < args.length; i++) {
            if ("IDLE".equalsIgnoreCase(args[i]) && i + 1 < args.length) {
                try {
                    minIdleTime = Long.parseLong(args[++i]);
                } catch (NumberFormatException e) {
                    return "-ERR value is not an integer or out of range\r\n";
                }
            } else if (i == 6) {
                // consumer 参数
                consumer = args[i];
            }
        }

        List<Map<String, Object>> pendingList = store.xpendingList(
            database, key, group, start, end, count, consumer, minIdleTime);

        return encodePendingList(pendingList);
    }

    /**
     * 编码待处理消息摘要
     */
    private String encodePendingSummary(Map<String, Object> summary) {
        StringBuilder sb = new StringBuilder();
        sb.append("*4\r\n");
        
        // 总数
        Long count = (Long) summary.get("count");
        sb.append(":").append(count != null ? count : 0).append("\r\n");
        
        // 最小 ID
        StreamId minId = (StreamId) summary.get("minId");
        if (minId != null) {
            String idStr = minId.toString();
            sb.append("$").append(idStr.length()).append("\r\n").append(idStr).append("\r\n");
        } else {
            sb.append("$-1\r\n");
        }
        
        // 最大 ID
        StreamId maxId = (StreamId) summary.get("maxId");
        if (maxId != null) {
            String idStr = maxId.toString();
            sb.append("$").append(idStr.length()).append("\r\n").append(idStr).append("\r\n");
        } else {
            sb.append("$-1\r\n");
        }
        
        // 消费者列表
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> consumers = (List<Map<String, Object>>) summary.get("consumers");
        if (consumers != null && !consumers.isEmpty()) {
            sb.append("*").append(consumers.size()).append("\r\n");
            for (Map<String, Object> consumerInfo : consumers) {
                sb.append("*2\r\n");
                String name = (String) consumerInfo.get("name");
                sb.append("$").append(name.length()).append("\r\n").append(name).append("\r\n");
                Long pending = (Long) consumerInfo.get("pending");
                sb.append(":").append(pending != null ? pending : 0).append("\r\n");
            }
        } else {
            sb.append("*0\r\n");
        }
        
        return sb.toString();
    }

    /**
     * 编码待处理消息列表
     */
    private String encodePendingList(List<Map<String, Object>> pendingList) {
        if (pendingList == null || pendingList.isEmpty()) {
            return "*0\r\n";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("*").append(pendingList.size()).append("\r\n");

        for (Map<String, Object> entry : pendingList) {
            sb.append("*4\r\n");
            
            // 消息 ID
            StreamId id = (StreamId) entry.get("id");
            String idStr = id.toString();
            sb.append("$").append(idStr.length()).append("\r\n").append(idStr).append("\r\n");
            
            // 消费者名称
            String consumer = (String) entry.get("consumer");
            sb.append("$").append(consumer.length()).append("\r\n").append(consumer).append("\r\n");
            
            // 空闲时间
            Long idle = (Long) entry.get("idle");
            sb.append(":").append(idle != null ? idle : 0).append("\r\n");
            
            // 传递次数
            Integer deliveries = (Integer) entry.get("deliveries");
            sb.append(":").append(deliveries != null ? deliveries : 0).append("\r\n");
        }

        return sb.toString();
    }

    // ==================== XCLAIM 命令处理 ====================

    /**
     * 处理 XCLAIM 命令
     * 格式: XCLAIM key group consumer min-idle-time ID [ID ...] [IDLE ms] [TIME ms-unix-time] 
     *       [RETRYCOUNT count] [FORCE] [JUSTID]
     */
    private Object handleXClaim(int database, String[] args, MemoryStore store) {
        if (args.length < 6) {
            return "-ERR wrong number of arguments for 'xclaim' command\r\n";
        }

        String key = args[1];
        String group = args[2];
        String consumer = args[3];
        
        long minIdleTime;
        try {
            minIdleTime = Long.parseLong(args[4]);
        } catch (NumberFormatException e) {
            return "-ERR value is not an integer or out of range\r\n";
        }

        // 解析消息 IDs 和选项
        java.util.List<StreamId> idList = new java.util.ArrayList<>();
        boolean justId = false;
        boolean force = false;
        int index = 5;

        while (index < args.length) {
            String arg = args[index].toUpperCase();
            
            if ("JUSTID".equals(arg)) {
                justId = true;
                index++;
            } else if ("FORCE".equals(arg)) {
                force = true;
                index++;
            } else if ("IDLE".equals(arg) && index + 1 < args.length) {
                // 跳过 IDLE 参数
                index += 2;
            } else if ("TIME".equals(arg) && index + 1 < args.length) {
                // 跳过 TIME 参数
                index += 2;
            } else if ("RETRYCOUNT".equals(arg) && index + 1 < args.length) {
                // 跳过 RETRYCOUNT 参数
                index += 2;
            } else {
                // 解析消息 ID
                try {
                    idList.add(StreamId.parse(args[index]));
                } catch (IllegalArgumentException e) {
                    return "-ERR Invalid stream ID specified\r\n";
                }
                index++;
            }
        }

        if (idList.isEmpty()) {
            return "-ERR no message IDs specified\r\n";
        }

        StreamId[] ids = idList.toArray(new StreamId[0]);
        List<StreamEntry> claimed = store.xclaim(database, key, group, consumer, 
                                                  minIdleTime, ids, justId, force);

        return encodeClaimedEntries(claimed, justId);
    }

    /**
     * 编码转移的消息
     */
    private String encodeClaimedEntries(List<StreamEntry> entries, boolean justId) {
        if (entries == null || entries.isEmpty()) {
            return "*0\r\n";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("*").append(entries.size()).append("\r\n");

        for (StreamEntry entry : entries) {
            if (justId) {
                // 只返回 ID
                String idStr = entry.getId().toString();
                sb.append("$").append(idStr.length()).append("\r\n").append(idStr).append("\r\n");
            } else {
                // 返回完整消息
                sb.append(encodeStreamEntry(entry));
            }
        }

        return sb.toString();
    }

    // ==================== XAUTOCLAIM 命令处理 ====================

    /**
     * 处理 XAUTOCLAIM 命令
     * 格式: XAUTOCLAIM key group consumer min-idle-time start [COUNT count]
     */
    private Object handleXAutoClaim(int database, String[] args, MemoryStore store) {
        if (args.length < 6) {
            return "-ERR wrong number of arguments for 'xautoclaim' command\r\n";
        }

        String key = args[1];
        String group = args[2];
        String consumer = args[3];
        
        long minIdleTime;
        try {
            minIdleTime = Long.parseLong(args[4]);
        } catch (NumberFormatException e) {
            return "-ERR value is not an integer or out of range\r\n";
        }

        // 解析起始 ID
        StreamId start;
        try {
            start = StreamId.parse(args[5]);
        } catch (IllegalArgumentException e) {
            return "-ERR Invalid stream ID specified\r\n";
        }

        // 解析 COUNT 参数
        int count = 100; // 默认值
        for (int i = 6; i < args.length; i++) {
            if ("COUNT".equalsIgnoreCase(args[i]) && i + 1 < args.length) {
                try {
                    count = Integer.parseInt(args[++i]);
                } catch (NumberFormatException e) {
                    return "-ERR value is not an integer or out of range\r\n";
                }
            }
        }

        Map<String, Object> result = store.xautoclaim(database, key, group, consumer, 
                                                       minIdleTime, start, count);

        return encodeAutoClaimResult(result);
    }

    /**
     * 编码 XAUTOCLAIM 结果
     */
    private String encodeAutoClaimResult(Map<String, Object> result) {
        if (result == null) {
            return "*3\r\n:0\r\n*0\r\n*0\r\n";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("*3\r\n");

        // 下一个扫描起点
        StreamId nextId = (StreamId) result.get("nextId");
        if (nextId != null) {
            String idStr = nextId.toString();
            sb.append("$").append(idStr.length()).append("\r\n").append(idStr).append("\r\n");
        } else {
            sb.append("$-1\r\n");
        }

        // 转移的消息列表
        @SuppressWarnings("unchecked")
        List<StreamEntry> entries = (List<StreamEntry>) result.get("entries");
        if (entries != null && !entries.isEmpty()) {
            sb.append("*").append(entries.size()).append("\r\n");
            for (StreamEntry entry : entries) {
                sb.append(encodeStreamEntry(entry));
            }
        } else {
            sb.append("*0\r\n");
        }

        // 删除的消息 ID 列表
        @SuppressWarnings("unchecked")
        List<StreamId> deleted = (List<StreamId>) result.get("deleted");
        if (deleted != null && !deleted.isEmpty()) {
            sb.append("*").append(deleted.size()).append("\r\n");
            for (StreamId id : deleted) {
                String idStr = id.toString();
                sb.append("$").append(idStr.length()).append("\r\n").append(idStr).append("\r\n");
            }
        } else {
            sb.append("*0\r\n");
        }

        return sb.toString();
    }

    // ==================== 辅助方法 ====================

    /**
     * 编码 StreamEntry 为 RESP 格式
     */
    private String encodeStreamEntry(StreamEntry entry) {
        StringBuilder sb = new StringBuilder();
        
        // 消息条目是一个包含 2 个元素的数组：[id, field-value pairs]
        sb.append("*2\r\n");
        
        // 第一个元素：ID
        String idStr = entry.getId().toString();
        sb.append("$").append(idStr.length()).append("\r\n").append(idStr).append("\r\n");
        
        // 第二个元素：字段值对数组
        LinkedHashMap<String, String> fields = entry.getFieldsInternal();
        int fieldCount = fields.size() * 2;
        sb.append("*").append(fieldCount).append("\r\n");
        
        for (Map.Entry<String, String> fieldEntry : fields.entrySet()) {
            // 字段名
            String fieldName = fieldEntry.getKey();
            sb.append("$").append(fieldName.length()).append("\r\n")
              .append(fieldName).append("\r\n");
            
            // 字段值
            String fieldValue = fieldEntry.getValue();
            if (fieldValue == null) {
                sb.append("$-1\r\n");
            } else {
                sb.append("$").append(fieldValue.length()).append("\r\n")
                  .append(fieldValue).append("\r\n");
            }
        }
        
        return sb.toString();
    }

    @Override
    public Set<String> supportedCommands() {
        return supportedCommands;
    }
}
