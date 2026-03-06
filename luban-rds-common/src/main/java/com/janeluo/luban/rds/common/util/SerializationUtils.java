package com.janeluo.luban.rds.common.util;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.io.ByteBufferInput;
import com.esotericsoftware.kryo.io.ByteBufferOutput;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;
import com.esotericsoftware.kryo.util.Pool;

import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

public final class SerializationUtils {

    private static final int INITIAL_BUFFER_SIZE = 1024;
    private static final int MAX_BUFFER_SIZE = 64 * 1024 * 1024;

    private static final Pool<Kryo> kryoPool = new Pool<Kryo>(true, false, 16) {
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

    private static final Pool<Output> outputPool = new Pool<Output>(true, false, 16) {
        @Override
        protected Output create() {
            return new Output(INITIAL_BUFFER_SIZE, MAX_BUFFER_SIZE);
        }
    };

    private static final Pool<Input> inputPool = new Pool<Input>(true, false, 16) {
        @Override
        protected Input create() {
            return new Input(INITIAL_BUFFER_SIZE);
        }
    };

    private SerializationUtils() {
    }

    public static byte[] serialize(Object obj) {
        if (obj == null) {
            return null;
        }
        if (obj instanceof byte[]) {
            return (byte[]) obj;
        }
        if (obj instanceof String) {
            return ((String) obj).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        }

        Kryo kryo = kryoPool.obtain();
        Output output = outputPool.obtain();
        try {
            output.reset();
            kryo.writeClassAndObject(output, obj);
            return output.toBytes();
        } finally {
            outputPool.free(output);
            kryoPool.free(kryo);
        }
    }

    @SuppressWarnings("unchecked")
    public static <T> T deserialize(byte[] bytes) {
        if (bytes == null) {
            return null;
        }

        Kryo kryo = kryoPool.obtain();
        Input input = inputPool.obtain();
        try {
            input.setBuffer(bytes);
            return (T) kryo.readClassAndObject(input);
        } finally {
            inputPool.free(input);
            kryoPool.free(kryo);
        }
    }

    public static byte[] serializeString(String str) {
        if (str == null) {
            return null;
        }
        return str.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    public static String deserializeString(byte[] bytes) {
        if (bytes == null) {
            return null;
        }
        return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
    }

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

    public static byte[] serializeMap(Map<String, String> map) {
        if (map == null) {
            return null;
        }
        return serialize(map);
    }

    public static byte[] serializeList(List<String> list) {
        if (list == null) {
            return null;
        }
        return serialize(list);
    }

    public static byte[] serializeSet(Set<String> set) {
        if (set == null) {
            return null;
        }
        return serialize(set);
    }

    public static long estimateSize(byte[] bytes) {
        if (bytes == null) {
            return 0;
        }
        return bytes.length;
    }

    public static ByteBuffer serializeToByteBuffer(Object obj) {
        if (obj == null) {
            return null;
        }
        byte[] bytes = serialize(obj);
        return ByteBuffer.wrap(bytes);
    }

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