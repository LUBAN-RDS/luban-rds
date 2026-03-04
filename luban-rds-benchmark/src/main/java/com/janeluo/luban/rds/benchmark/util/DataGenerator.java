package com.janeluo.luban.rds.benchmark.util;

import java.util.Random;

public class DataGenerator {
    public static String generateValue(int size) {
        StringBuilder sb = new StringBuilder(size);
        Random random = new Random();
        for (int i = 0; i < size; i++) {
            sb.append((char) ('a' + random.nextInt(26)));
        }
        return sb.toString();
    }
}
