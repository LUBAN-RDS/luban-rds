package com.janeluo.luban.rds.common.util;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;
import com.esotericsoftware.kryo.util.Pool;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 序列化工具类
 * 基于 Kryo 实现高性能的对象序列化与反序列化
 */
public final class SerializationUtils {

    /** 初始缓冲区大小 */
    private static final int INITIAL_BUFFER_SIZE = 1024;

    /** 最大缓冲区大小 */
    private static final int MAX_BUFFER_SIZE = 64 * 1024 * 1024;

    /** Kryo 对象池 */
    private static final Pool<Kryo> KRYO_POOL = new Pool<Kryo>(true, false, 16) {
        @Override
        protected Kryo create() {
            Kryo kryo = new Kryo();
            kryo.setReferences(true);
            kryo.setRegistrationRequired(false);
            kryo.register(String.class);
            kryo.register(HashMap.class);
            kryo.register(ConcurrentHashMap.class);
            kryo.register(ArrayList.class);
            kryo.register(CopyOnWriteArrayList.class);
            kryo.register(HashSet.class);
            kryo.register(LinkedHashSet.class);
            kryo.register(TreeSet.class);
            kryo.register(ConcurrentHashMap.KeySetView.class);
            kryo.register(byte[].class);
            return kryo;
        }
    };

    /** Output 对象池 */
    private static final Pool<Output> OUTPUT_POOL = new Pool<Output>(true, false, 16) {
        @Override
        protected Output create() {
            return new Output(INITIAL_BUFFER_SIZE, MAX_BUFFER_SIZE);
        }
    };

    /** Input 对象池 */
    private static final Pool<Input> INPUT_POOL = new Pool<Input>(true, false, 16) {
        @Override
        protected Input create() {
            return new Input(INITIAL_BUFFER_SIZE);
        }
    };

    private SerializationUtils() {
    }

    /**
     * 序列化对象为字节数组
     *
     * @param obj 要序列化的对象
     * @return 序列化后的字节数组，对象为 null 时返回 null
     */
    public static byte[] serialize(Object obj) {
        if (obj == null) {
            return null;
        }
        if (obj instanceof byte[]) {
            return (byte[]) obj;
        }
        if (obj instanceof String) {
            return ((String) obj).getBytes(StandardCharsets.UTF_8);
        }

        Kryo kryo = KRYO_POOL.obtain();
        Output output = OUTPUT_POOL.obtain();
        try {
            output.reset();
            kryo.writeClassAndObject(output, obj);
            return output.toBytes();
        } finally {
            OUTPUT_POOL.free(output);
            KRYO_POOL.free(kryo);
        }
    }

    /**
     * 反序列化字节数组为对象
     *
     * @param bytes 字节数组
     * @param <T>   目标类型
     * @return 反序列化后的对象，字节数组为 null 时返回 null
     */
    @SuppressWarnings("unchecked")
    public static <T> T deserialize(byte[] bytes) {
        if (bytes == null) {
            return null;
        }

        Kryo kryo = KRYO_POOL.obtain();
        Input input = INPUT_POOL.obtain();
        try {
            input.setBuffer(bytes);
            return (T) kryo.readClassAndObject(input);
        } finally {
            INPUT_POOL.free(input);
            KRYO_POOL.free(kryo);
        }
    }

    /**
     * 序列化字符串为字节数组
     *
     * @param str 字符串
     * @return UTF-8 编码的字节数组，字符串为 null 时返回 null
     */
    public static byte[] serializeString(String str) {
        if (str == null) {
            return null;
        }
        return str.getBytes(StandardCharsets.UTF_8);
    }

    /**
     * 反序列化字节数组为字符串
     *
     * @param bytes 字节数组
     * @return UTF-8 解码的字符串，字节数组为 null 时返回 null
     */
    public static String deserializeString(byte[] bytes) {
        if (bytes == null) {
            return null;
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }

    /**
     * 反序列化字节数组为 Map
     *
     * @param bytes 字节数组
     * @return Map 对象，反序列化失败或字节数组为 null 时返回 null
     */
    @SuppressWarnings("unchecked")
    public static Map<String, String> deserializeToMap(byte[] bytes) {
        if (bytes == null) {
            return null;
        }
        Object obj = deserialize(bytes);
        if (obj instanceof Map) {
            return (Map<String, String>) obj;
        }
        return null;
    }

    /**
     * 反序列化字节数组为 List
     *
     * @param bytes 字节数组
     * @return List 对象，反序列化失败或字节数组为 null 时返回 null
     */
    @SuppressWarnings("unchecked")
    public static List<String> deserializeToList(byte[] bytes) {
        if (bytes == null) {
            return null;
        }
        Object obj = deserialize(bytes);
        if (obj instanceof List) {
            return (List<String>) obj;
        }
        return null;
    }

    /**
     * 反序列化字节数组为 Set
     *
     * @param bytes 字节数组
     * @return Set 对象，反序列化失败或字节数组为 null 时返回 null
     */
    @SuppressWarnings("unchecked")
    public static Set<String> deserializeToSet(byte[] bytes) {
        if (bytes == null) {
            return null;
        }
        Object obj = deserialize(bytes);
        if (obj instanceof Set) {
            return (Set<String>) obj;
        }
        return null;
    }

    /**
     * 序列化 Map 为字节数组
     *
     * @param map Map 对象
     * @return 序列化后的字节数组，Map 为 null 时返回 null
     */
    public static byte[] serializeMap(Map<String, String> map) {
        if (map == null) {
            return null;
        }
        return serialize(map);
    }

    /**
     * 序列化 List 为字节数组
     *
     * @param list List 对象
     * @return 序列化后的字节数组，List 为 null 时返回 null
     */
    public static byte[] serializeList(List<String> list) {
        if (list == null) {
            return null;
        }
        return serialize(list);
    }

    /**
     * 序列化 Set 为字节数组
     *
     * @param set Set 对象
     * @return 序列化后的字节数组，Set 为 null 时返回 null
     */
    public static byte[] serializeSet(Set<String> set) {
        if (set == null) {
            return null;
        }
        return serialize(set);
    }

    /**
     * 估算字节数组的大小
     *
     * @param bytes 字节数组
     * @return 字节数组的长度，字节数组为 null 时返回 0
     */
    public static long estimateSize(byte[] bytes) {
        if (bytes == null) {
            return 0;
        }
        return bytes.length;
    }

    /**
     * 序列化对象为 ByteBuffer
     *
     * @param obj 要序列化的对象
     * @return 包含序列化数据的 ByteBuffer，对象为 null 时返回 null
     */
    public static ByteBuffer serializeToByteBuffer(Object obj) {
        if (obj == null) {
            return null;
        }
        byte[] bytes = serialize(obj);
        return ByteBuffer.wrap(bytes);
    }

    /**
     * 反序列化 ByteBuffer 为对象
     *
     * @param buffer ByteBuffer
     * @param <T>    目标类型
     * @return 反序列化后的对象，ByteBuffer 为 null 时返回 null
     */
    @SuppressWarnings("unchecked")
    public static <T> T deserializeFromByteBuffer(ByteBuffer buffer) {
        if (buffer == null) {
            return null;
        }
        byte[] bytes = new byte[buffer.remaining()];
        buffer.get(bytes);
        return deserialize(bytes);
    }
}
