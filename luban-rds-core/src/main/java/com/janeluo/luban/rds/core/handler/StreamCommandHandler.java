package com.janeluo.luban.rds.core.handler;

import com.janeluo.luban.rds.common.constant.RdsCommandConstant;
import com.janeluo.luban.rds.core.stream.BlockingResult;
import com.janeluo.luban.rds.core.stream.Stream;
import com.janeluo.luban.rds.core.stream.StreamConsumerGroupManager;
import com.janeluo.luban.rds.core.stream.StreamEntry;
import com.janeluo.luban.rds.core.stream.StreamId;
import com.janeluo.luban.rds.core.store.MemoryStore;
import com.google.common.collect.Sets;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Stream 类型命令处理器
 * 
 * <p>负责处理 Redis Stream 类型相关的所有命令，包括：
 * <ul>
 *   <li>XADD - 向流添加消息</li>
 *   <li>XLEN - 获取流长度</li>
 *   <li>XRANGE - 范围查询（正序）</li>
 *   <li>XREVRANGE - 范围查询（逆序）</li>
 *   <li>XDEL - 删除消息</li>
 *   <li>XTRIM - 裁剪流</li>
 *   <li>XREAD - 读取消息</li>
 *   <li>XINFO - 获取流信息</li>
 * </ul>
 * 
 * @author janeluo
 * @since 1.0.0
 */
public class StreamCommandHandler implements CommandHandler {

    private final Set<String> supportedCommands = Sets.newHashSet(
        RdsCommandConstant.XADD,
        RdsCommandConstant.XLEN,
        RdsCommandConstant.XRANGE,
        RdsCommandConstant.XREVRANGE,
        RdsCommandConstant.XDEL,
        RdsCommandConstant.XTRIM,
        RdsCommandConstant.XREAD,
        RdsCommandConstant.XINFO
    );

    @Override
    public Object handle(int database, String[] args, MemoryStore store) {
        String command = args[0].toUpperCase();

        switch (command) {
            case RdsCommandConstant.XADD:
                return handleXAdd(database, args, store);
            case RdsCommandConstant.XLEN:
                return handleXLen(database, args, store);
            case RdsCommandConstant.XRANGE:
                return handleXRange(database, args, store);
            case RdsCommandConstant.XREVRANGE:
                return handleXRevRange(database, args, store);
            case RdsCommandConstant.XDEL:
                return handleXDel(database, args, store);
            case RdsCommandConstant.XTRIM:
                return handleXTrim(database, args, store);
            case RdsCommandConstant.XREAD:
                return handleXRead(database, args, store);
            case RdsCommandConstant.XINFO:
                return handleXInfo(database, args, store);
            default:
                return "-ERR unknown command\r\n";
        }
    }

    /**
     * 处理 XADD 命令
     * 
     * <p>格式: XADD key [NOMKSTREAM] [MAXLEN|MINID [=|~] threshold [LIMIT count]] *|ID field value [field value ...]
     */
    private Object handleXAdd(int database, String[] args, MemoryStore store) {
        if (args.length < 4) {
            return "-ERR wrong number of arguments for 'xadd' command\r\n";
        }

        String key = args[1];
        int index = 2;
        boolean noMkStream = false;
        Long maxLen = null;
        StreamId minId = null;
        boolean approximateTrim = false;
        Integer limit = null;

        // 解析选项
        while (index < args.length) {
            String option = args[index].toUpperCase();

            if ("NOMKSTREAM".equals(option)) {
                noMkStream = true;
                index++;
            } else if ("MAXLEN".equals(option)) {
                index++;
                if (index >= args.length) {
                    return "-ERR syntax error\r\n";
                }
                // 检查是否是近似裁剪
                if ("=".equals(args[index]) || "~".equals(args[index])) {
                    approximateTrim = "~".equals(args[index]);
                    index++;
                }
                if (index >= args.length) {
                    return "-ERR syntax error\r\n";
                }
                try {
                    maxLen = Long.parseLong(args[index]);
                    index++;
                } catch (NumberFormatException e) {
                    return "-ERR value is not an integer or out of range\r\n";
                }
                // 检查 LIMIT 选项
                if (index < args.length && "LIMIT".equalsIgnoreCase(args[index])) {
                    index++;
                    if (index >= args.length) {
                        return "-ERR syntax error\r\n";
                    }
                    try {
                        limit = Integer.parseInt(args[index]);
                        index++;
                    } catch (NumberFormatException e) {
                        return "-ERR value is not an integer or out of range\r\n";
                    }
                }
            } else if ("MINID".equals(option)) {
                index++;
                if (index >= args.length) {
                    return "-ERR syntax error\r\n";
                }
                // 检查是否是近似裁剪
                if ("=".equals(args[index]) || "~".equals(args[index])) {
                    approximateTrim = "~".equals(args[index]);
                    index++;
                }
                if (index >= args.length) {
                    return "-ERR syntax error\r\n";
                }
                try {
                    minId = StreamId.parse(args[index]);
                    index++;
                } catch (IllegalArgumentException e) {
                    return "-ERR Invalid stream ID specified as stream command argument\r\n";
                }
                // 检查 LIMIT 选项
                if (index < args.length && "LIMIT".equalsIgnoreCase(args[index])) {
                    index++;
                    if (index >= args.length) {
                        return "-ERR syntax error\r\n";
                    }
                    try {
                        limit = Integer.parseInt(args[index]);
                        index++;
                    } catch (NumberFormatException e) {
                        return "-ERR value is not an integer or out of range\r\n";
                    }
                }
            } else {
                // 遇到 ID 或字段，退出选项解析
                break;
            }
        }

        // 解析 ID
        if (index >= args.length) {
            return "-ERR wrong number of arguments for 'xadd' command\r\n";
        }

        String idStr = args[index];
        StreamId id = null;

        if (!"*".equals(idStr)) {
            try {
                id = StreamId.parse(idStr);
                // 验证 ID 格式：如果是完整 ID（带 -），序号不能是负数
                if (idStr.contains("-")) {
                    String[] parts = idStr.split("-");
                    if (parts.length == 2) {
                        try {
                            long seq = Long.parseLong(parts[1]);
                            if (seq < 0) {
                                return "-ERR Invalid stream ID specified as stream command argument\r\n";
                            }
                        } catch (NumberFormatException e) {
                            return "-ERR Invalid stream ID specified as stream command argument\r\n";
                        }
                    }
                }
            } catch (IllegalArgumentException e) {
                return "-ERR Invalid stream ID specified as stream command argument\r\n";
            }
        }
        index++;

        // 解析字段值对
        if (index >= args.length) {
            return "-ERR wrong number of arguments for 'xadd' command\r\n";
        }

        if ((args.length - index) % 2 != 0) {
            return "-ERR wrong number of arguments for 'xadd' command\r\n";
        }

        Map<String, String> fields = new LinkedHashMap<>();
        while (index < args.length) {
            String field = args[index];
            String value = args[index + 1];
            fields.put(field, value);
            index += 2;
        }

        // 获取或创建 Stream
        Object existing = store.get(database, key);
        Stream stream;

        if (existing == null) {
            if (noMkStream) {
                return "$-1\r\n";
            }
            stream = new Stream();
            store.set(database, key, stream);
        } else {
            if (!(existing instanceof Stream)) {
                return "-WRONGTYPE Operation against a key holding the wrong kind of value\r\n";
            }
            stream = (Stream) existing;
        }

        // 验证 ID 必须大于最后生成的 ID
        if (id != null) {
            StreamId lastId = stream.getLastGeneratedId();
            if (lastId != null && id.compareTo(lastId) <= 0) {
                return "-ERR The ID specified in XADD is equal or smaller than the target stream top item\r\n";
            }
        }

        // 添加消息
        StreamId generatedId;
        try {
            generatedId = stream.addEntry(id, fields);
        } catch (IllegalArgumentException e) {
            return "-ERR The ID specified in XADD is equal or smaller than the target stream top item\r\n";
        }

        // 执行裁剪
        if (maxLen != null) {
            stream.trim(maxLen.intValue());
        } else if (minId != null) {
            stream.trim(minId);
        }

        // 返回生成的 ID (Bulk String 格式)
        String idResult = generatedId.toString();
        return "$" + idResult.length() + "\r\n" + idResult + "\r\n";
    }

    /**
     * 处理 XLEN 命令
     * 
     * <p>格式: XLEN key
     */
    private Object handleXLen(int database, String[] args, MemoryStore store) {
        if (args.length < 2) {
            return "-ERR wrong number of arguments for 'xlen' command\r\n";
        }

        String key = args[1];
        Object value = store.get(database, key);

        if (value == null) {
            return ":0\r\n";
        }

        if (!(value instanceof Stream)) {
            return "-WRONGTYPE Operation against a key holding the wrong kind of value\r\n";
        }

        Stream stream = (Stream) value;
        return ":" + stream.getLength() + "\r\n";
    }

    /**
     * 处理 XRANGE 命令
     * 
     * <p>格式: XRANGE key start end [COUNT count]
     */
    private Object handleXRange(int database, String[] args, MemoryStore store) {
        if (args.length < 4) {
            return "-ERR wrong number of arguments for 'xrange' command\r\n";
        }

        String key = args[1];
        Object value = store.get(database, key);

        if (value == null) {
            return "*0\r\n";
        }

        if (!(value instanceof Stream)) {
            return "-WRONGTYPE Operation against a key holding the wrong kind of value\r\n";
        }

        Stream stream = (Stream) value;

        // 解析起始和结束 ID
        boolean exclusiveStart = false;
        boolean exclusiveEnd = false;
        String startStr = args[2];
        String endStr = args[3];

        // 处理开区间标记
        if (startStr.startsWith("(")) {
            exclusiveStart = true;
            startStr = startStr.substring(1);
        }
        if (endStr.startsWith("(")) {
            exclusiveEnd = true;
            endStr = endStr.substring(1);
        }

        StreamId start;
        StreamId end;

        try {
            // 处理特殊 ID
            if ("-".equals(startStr)) {
                start = StreamId.MIN_ID;
                exclusiveStart = false; // 最小 ID 不需要排除
            } else {
                start = StreamId.parse(startStr);
            }

            if ("+".equals(endStr)) {
                end = StreamId.MAX_ID;
                exclusiveEnd = false; // 最大 ID 不需要排除
            } else {
                end = StreamId.parse(endStr);
            }
        } catch (IllegalArgumentException e) {
            return "-ERR Invalid stream ID specified as stream command argument\r\n";
        }

        // 解析 COUNT 选项
        int count = -1;
        for (int i = 4; i < args.length; i++) {
            if ("COUNT".equalsIgnoreCase(args[i]) && i + 1 < args.length) {
                try {
                    count = Integer.parseInt(args[i + 1]);
                    i++;
                } catch (NumberFormatException e) {
                    return "-ERR value is not an integer or out of range\r\n";
                }
            }
        }

        // 获取范围内的消息
        List<StreamEntry> entries = stream.getRange(start, end, exclusiveStart, exclusiveEnd, count);

        // 构建响应
        return buildStreamEntriesResponse(entries);
    }

    /**
     * 处理 XREVRANGE 命令
     * 
     * <p>格式: XREVRANGE key end start [COUNT count]
     */
    private Object handleXRevRange(int database, String[] args, MemoryStore store) {
        if (args.length < 4) {
            return "-ERR wrong number of arguments for 'xrevrange' command\r\n";
        }

        String key = args[1];
        Object value = store.get(database, key);

        if (value == null) {
            return "*0\r\n";
        }

        if (!(value instanceof Stream)) {
            return "-WRONGTYPE Operation against a key holding the wrong kind of value\r\n";
        }

        Stream stream = (Stream) value;

        // 注意：XREVRANGE 的参数顺序是 end start（与 XRANGE 相反）
        boolean exclusiveEnd = false;
        boolean exclusiveStart = false;
        String endStr = args[2];
        String startStr = args[3];

        // 处理开区间标记
        if (endStr.startsWith("(")) {
            exclusiveEnd = true;
            endStr = endStr.substring(1);
        }
        if (startStr.startsWith("(")) {
            exclusiveStart = true;
            startStr = startStr.substring(1);
        }

        StreamId start;
        StreamId end;

        try {
            // 处理特殊 ID
            if ("-".equals(startStr)) {
                start = StreamId.MIN_ID;
                exclusiveStart = false;
            } else {
                start = StreamId.parse(startStr);
            }

            if ("+".equals(endStr)) {
                end = StreamId.MAX_ID;
                exclusiveEnd = false;
            } else {
                end = StreamId.parse(endStr);
            }
        } catch (IllegalArgumentException e) {
            return "-ERR Invalid stream ID specified as stream command argument\r\n";
        }

        // 解析 COUNT 选项
        int count = -1;
        for (int i = 4; i < args.length; i++) {
            if ("COUNT".equalsIgnoreCase(args[i]) && i + 1 < args.length) {
                try {
                    count = Integer.parseInt(args[i + 1]);
                    i++;
                } catch (NumberFormatException e) {
                    return "-ERR value is not an integer or out of range\r\n";
                }
            }
        }

        // 获取范围内的消息（正序）
        List<StreamEntry> entries = stream.getRange(start, end, exclusiveStart, exclusiveEnd, count);

        // 反转结果
        java.util.Collections.reverse(entries);

        // 构建响应
        return buildStreamEntriesResponse(entries);
    }

    /**
     * 处理 XDEL 命令
     * 
     * <p>格式: XDEL key ID [ID ...]
     */
    private Object handleXDel(int database, String[] args, MemoryStore store) {
        if (args.length < 3) {
            return "-ERR wrong number of arguments for 'xdel' command\r\n";
        }

        String key = args[1];
        Object value = store.get(database, key);

        if (value == null) {
            return ":0\r\n";
        }

        if (!(value instanceof Stream)) {
            return "-WRONGTYPE Operation against a key holding the wrong kind of value\r\n";
        }

        Stream stream = (Stream) value;

        int deleted = 0;
        for (int i = 2; i < args.length; i++) {
            try {
                StreamId id = StreamId.parse(args[i]);
                if (stream.deleteEntry(id)) {
                    deleted++;
                }
            } catch (IllegalArgumentException e) {
                return "-ERR Invalid stream ID specified as stream command argument\r\n";
            }
        }

        return ":" + deleted + "\r\n";
    }

    /**
     * 处理 XTRIM 命令
     * 
     * <p>格式: XTRIM key MAXLEN|MINID [=|~] threshold [LIMIT count]
     */
    private Object handleXTrim(int database, String[] args, MemoryStore store) {
        if (args.length < 4) {
            return "-ERR wrong number of arguments for 'xtrim' command\r\n";
        }

        String key = args[1];
        Object value = store.get(database, key);

        if (value == null) {
            return ":0\r\n";
        }

        if (!(value instanceof Stream)) {
            return "-WRONGTYPE Operation against a key holding the wrong kind of value\r\n";
        }

        Stream stream = (Stream) value;

        String strategy = args[2].toUpperCase();
        int index = 3;

        // 检查是否是近似裁剪
        boolean approximateTrim = false;
        if ("=".equals(args[index]) || "~".equals(args[index])) {
            approximateTrim = "~".equals(args[index]);
            index++;
        }

        if (index >= args.length) {
            return "-ERR syntax error\r\n";
        }

        int trimmed;

        if ("MAXLEN".equals(strategy)) {
            long maxLen;
            try {
                maxLen = Long.parseLong(args[index]);
                index++;
            } catch (NumberFormatException e) {
                return "-ERR value is not an integer or out of range\r\n";
            }
            trimmed = stream.trim((int) maxLen);
        } else if ("MINID".equals(strategy)) {
            StreamId minId;
            try {
                minId = StreamId.parse(args[index]);
                index++;
            } catch (IllegalArgumentException e) {
                return "-ERR Invalid stream ID specified as stream command argument\r\n";
            }
            trimmed = stream.trim(minId);
        } else {
            return "-ERR syntax error\r\n";
        }

        return ":" + trimmed + "\r\n";
    }

    /**
     * 处理 XREAD 命令
     * 
     * <p>格式: XREAD [COUNT count] [BLOCK milliseconds] STREAMS key [key ...] ID [ID ...]
     * 
     * <p>当指定 BLOCK 选项且没有新消息时，返回 BlockingResult 对象，
     * 由 RedisServerHandler 处理阻塞逻辑。
     */
    private Object handleXRead(int database, String[] args, MemoryStore store) {
        if (args.length < 4) {
            return "-ERR wrong number of arguments for 'xread' command\r\n";
        }

        int index = 1;
        int count = -1;
        long blockTimeout = -1; // -1 表示没有 BLOCK 选项

        // 解析选项
        while (index < args.length) {
            String option = args[index].toUpperCase();

            if ("COUNT".equals(option)) {
                index++;
                if (index >= args.length) {
                    return "-ERR syntax error\r\n";
                }
                try {
                    count = Integer.parseInt(args[index]);
                    index++;
                } catch (NumberFormatException e) {
                    return "-ERR value is not an integer or out of range\r\n";
                }
            } else if ("BLOCK".equals(option)) {
                index++;
                if (index >= args.length) {
                    return "-ERR syntax error\r\n";
                }
                try {
                    blockTimeout = Long.parseLong(args[index]);
                    index++;
                } catch (NumberFormatException e) {
                    return "-ERR value is not an integer or out of range\r\n";
                }
            } else if ("STREAMS".equals(option)) {
                index++;
                break;
            } else {
                index++;
            }
        }

        if (index >= args.length) {
            return "-ERR syntax error\r\n";
        }

        // 解析 keys 和 IDs
        // STREAMS 后面的参数：key1 key2 ... id1 id2 ...
        // 前半部分是 keys，后半部分是 IDs
        int remaining = args.length - index;
        if (remaining < 2 || remaining % 2 != 0) {
            return "-ERR syntax error\r\n";
        }

        int keyCount = remaining / 2;
        String[] keys = new String[keyCount];
        StreamId[] ids = new StreamId[keyCount];

        for (int i = 0; i < keyCount; i++) {
            keys[i] = args[index + i];
        }

        for (int i = 0; i < keyCount; i++) {
            String idStr = args[index + keyCount + i];
            if ("$".equals(idStr)) {
                // $ 表示最新消息之后，非阻塞模式返回空
                ids[i] = null;
            } else {
                try {
                    ids[i] = StreamId.parse(idStr);
                } catch (IllegalArgumentException e) {
                    return "-ERR Invalid stream ID specified as stream command argument\r\n";
                }
            }
        }

        // 尝试读取消息
        List<String> resultKeys = new ArrayList<>();
        List<List<StreamEntry>> resultEntries = new ArrayList<>();

        for (int i = 0; i < keyCount; i++) {
            String key = keys[i];
            StreamId startId = ids[i];
            Object value = store.get(database, key);

            if (value == null) {
                continue;
            }

            if (!(value instanceof Stream)) {
                return "-WRONGTYPE Operation against a key holding the wrong kind of value\r\n";
            }

            Stream stream = (Stream) value;

            // 如果 ID 为 null（$），获取最后生成的 ID 作为起始点
            StreamId effectiveStartId = startId;
            if (effectiveStartId == null) {
                effectiveStartId = stream.getLastGeneratedId();
                if (effectiveStartId == null) {
                    // 流为空，没有消息
                    continue;
                }
            }

            // 从指定 ID 之后读取消息
            List<StreamEntry> entries;
            if (count > 0) {
                entries = stream.getRangeFrom(effectiveStartId, true, count);
            } else {
                entries = stream.getRangeFrom(effectiveStartId, true, Integer.MAX_VALUE);
            }

            if (!entries.isEmpty()) {
                resultKeys.add(key);
                resultEntries.add(entries);
            }
        }

        // 如果有消息，返回结果
        if (!resultEntries.isEmpty()) {
            return buildXReadResponse(resultKeys, resultEntries);
        }

        // 没有消息的情况
        if (blockTimeout >= 0) {
            // 阻塞模式：返回 BlockingResult，由 RedisServerHandler 处理阻塞
            List<String> keyList = new ArrayList<>();
            List<StreamId> idList = new ArrayList<>();
            
            for (int i = 0; i < keyCount; i++) {
                keyList.add(keys[i]);
                // 如果 ID 是 $，需要获取当前最后 ID
                StreamId startId = ids[i];
                if (startId == null) {
                    Object value = store.get(database, keys[i]);
                    if (value instanceof Stream) {
                        Stream stream = (Stream) value;
                        startId = stream.getLastGeneratedId();
                    }
                    if (startId == null) {
                        startId = StreamId.MIN_ID;
                    }
                }
                idList.add(startId);
            }
            
            return BlockingResult.forXRead(database, keyList, idList, blockTimeout, count);
        } else {
            // 非阻塞模式：返回空结果
            return "$-1\r\n";
        }
    }

    /**
     * 构建 XREAD 响应
     */
    private String buildXReadResponse(List<String> keys, List<List<StreamEntry>> entriesList) {
        StringBuilder result = new StringBuilder();
        result.append("*").append(keys.size()).append("\r\n");

        for (int i = 0; i < keys.size(); i++) {
            String key = keys.get(i);
            List<StreamEntry> entries = entriesList.get(i);

            // 每个流是一个包含 2 个元素的数组：[key, entries]
            result.append("*2\r\n");

            // key
            byte[] keyBytes = key.getBytes(StandardCharsets.UTF_8);
            result.append("$").append(keyBytes.length).append("\r\n").append(key).append("\r\n");

            // entries
            result.append(buildStreamEntriesResponse(entries));
        }

        return result.toString();
    }

    /**
     * 处理 XINFO 命令
     * 
     * <p>格式:
     * - XINFO STREAM key
     * - XINFO GROUPS key
     * - XINFO CONSUMERS key group
     */
    private Object handleXInfo(int database, String[] args, MemoryStore store) {
        if (args.length < 3) {
            return "-ERR wrong number of arguments for 'xinfo' command\r\n";
        }

        String subCommand = args[1].toUpperCase();
        String key = args[2];

        Object value = store.get(database, key);

        if (value == null) {
            return "-ERR no such key\r\n";
        }

        if (!(value instanceof Stream)) {
            return "-WRONGTYPE Operation against a key holding the wrong kind of value\r\n";
        }

        Stream stream = (Stream) value;

        switch (subCommand) {
            case "STREAM":
                return buildStreamInfo(stream, key);
            case "GROUPS":
                return buildGroupsInfo(stream, key);
            case "CONSUMERS":
                if (args.length < 4) {
                    return "-ERR wrong number of arguments for 'xinfo|consumers' command\r\n";
                }
                return buildConsumersInfo(stream, key, args[3]);
            default:
                return "-ERR syntax error\r\n";
        }
    }

    /**
     * 构建 Stream 信息响应
     */
    private Object buildStreamInfo(Stream stream, String key) {
        StringBuilder result = new StringBuilder();

        // 返回一个包含多个字段的数组
        result.append("*13\r\n");

        // length
        result.append("$6\r\nlength\r\n");
        result.append(":").append(stream.getLength()).append("\r\n");

        // radix-tree-keys (简化处理，返回消息数量)
        result.append("$15\r\nradix-tree-keys\r\n");
        result.append(":").append(stream.getLength()).append("\r\n");

        // radix-tree-nodes (简化处理)
        result.append("$16\r\nradix-tree-nodes\r\n");
        result.append(":").append(stream.getLength() + 1).append("\r\n");

        // groups (从 StreamConsumerGroupManager 获取)
        result.append("$6\r\ngroups\r\n");
        int groupCount = getGroupCount(stream, key);
        result.append(":").append(groupCount).append("\r\n");

        // last-generated-id
        result.append("$17\r\nlast-generated-id\r\n");
        StreamId lastId = stream.getLastGeneratedId();
        if (lastId != null) {
            String idStr = lastId.toString();
            result.append("$").append(idStr.length()).append("\r\n").append(idStr).append("\r\n");
        } else {
            result.append("$-1\r\n");
        }

        // first-entry
        result.append("$11\r\nfirst-entry\r\n");
        StreamEntry firstEntry = stream.getFirstEntry();
        if (firstEntry != null) {
            result.append(firstEntry.toRespString());
        } else {
            result.append("$-1\r\n");
        }

        // last-entry
        result.append("$10\r\nlast-entry\r\n");
        StreamEntry lastEntry = stream.getLastEntry();
        if (lastEntry != null) {
            result.append(lastEntry.toRespString());
        } else {
            result.append("$-1\r\n");
        }

        return result.toString();
    }

    /**
     * 构建消费者组信息响应
     */
    private Object buildGroupsInfo(Stream stream, String key) {
        StreamConsumerGroupManager manager = getGroupManager(stream, key);
        if (manager == null || manager.isEmpty()) {
            return "*0\r\n";
        }

        List<com.janeluo.luban.rds.core.stream.ConsumerGroup> groups = manager.getGroups();
        StringBuilder result = new StringBuilder();
        result.append("*").append(groups.size()).append("\r\n");

        for (com.janeluo.luban.rds.core.stream.ConsumerGroup group : groups) {
            // 每个组返回一个包含多个字段的数组
            result.append("*8\r\n");

            // name
            result.append("$4\r\nname\r\n");
            String groupName = group.getName();
            result.append("$").append(groupName.length()).append("\r\n").append(groupName).append("\r\n");

            // consumers
            result.append("$9\r\nconsumers\r\n");
            result.append(":").append(group.getConsumerCount()).append("\r\n");

            // pending
            result.append("$7\r\npending\r\n");
            result.append(":").append(group.getPendingCount()).append("\r\n");

            // last-delivered-id
            result.append("$16\r\nlast-delivered-id\r\n");
            StreamId lastDeliveredId = group.getLastDeliveredId();
            if (lastDeliveredId != null) {
                String idStr = lastDeliveredId.toString();
                result.append("$").append(idStr.length()).append("\r\n").append(idStr).append("\r\n");
            } else {
                result.append("$-1\r\n");
            }
        }

        return result.toString();
    }

    /**
     * 构建消费者信息响应
     */
    private Object buildConsumersInfo(Stream stream, String key, String groupName) {
        StreamConsumerGroupManager manager = getGroupManager(stream, key);
        if (manager == null) {
            return "*0\r\n";
        }

        com.janeluo.luban.rds.core.stream.ConsumerGroup group = manager.getGroup(groupName);
        if (group == null) {
            return "-ERR no such consumer group\r\n";
        }

        List<com.janeluo.luban.rds.core.stream.Consumer> consumers = group.getConsumers();
        StringBuilder result = new StringBuilder();
        result.append("*").append(consumers.size()).append("\r\n");

        for (com.janeluo.luban.rds.core.stream.Consumer consumer : consumers) {
            // 每个消费者返回一个包含多个字段的数组
            result.append("*6\r\n");

            // name
            result.append("$4\r\nname\r\n");
            String consumerName = consumer.getName();
            result.append("$").append(consumerName.length()).append("\r\n").append(consumerName).append("\r\n");

            // pending
            result.append("$7\r\npending\r\n");
            result.append(":").append(consumer.getPendingCount()).append("\r\n");

            // idle
            result.append("$4\r\nidle\r\n");
            result.append(":").append(consumer.getIdleTime()).append("\r\n");
        }

        return result.toString();
    }

    /**
     * 构建消息条目列表的响应
     */
    private String buildStreamEntriesResponse(List<StreamEntry> entries) {
        if (entries.isEmpty()) {
            return "*0\r\n";
        }

        StringBuilder result = new StringBuilder();
        result.append("*").append(entries.size()).append("\r\n");

        for (StreamEntry entry : entries) {
            result.append(entry.toRespString());
        }

        return result.toString();
    }

    /**
     * 获取消费者组数量
     */
    private int getGroupCount(Stream stream, String key) {
        // 暂时返回 0，后续实现消费者组管理后更新
        return 0;
    }

    /**
     * 获取消费者组管理器
     */
    private StreamConsumerGroupManager getGroupManager(Stream stream, String key) {
        // 暂时返回 null，后续实现消费者组管理后更新
        return null;
    }

    @Override
    public Set<String> supportedCommands() {
        return supportedCommands;
    }
}
