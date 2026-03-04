package com.janeluo.luban.rds.common.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class RdsUtil {
    private static final Logger logger = LoggerFactory.getLogger(RdsUtil.class);
    
    public static Logger getLogger(Class<?> clazz) {
        return LoggerFactory.getLogger(clazz);
    }
    
    public static String toString(Object obj) {
        if (obj == null) {
            return null;
        }
        return obj.toString();
    }
    
    public static boolean isEmpty(String str) {
        return str == null || str.isEmpty();
    }
    
    public static boolean isNotEmpty(String str) {
        return !isEmpty(str);
    }
    
    public static long currentTimeMillis() {
        return System.currentTimeMillis();
    }
    
    public static long currentSeconds() {
        return currentTimeMillis() / 1000;
    }
}
