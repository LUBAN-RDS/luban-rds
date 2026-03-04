package com.janeluo.luban.rds.protocol;

public enum RespType {
    SIMPLE_STRING('+'),
    ERROR('-'),
    INTEGER(':'),
    BULK_STRING('$'),
    ARRAY('*');
    
    private final char prefix;
    
    RespType(char prefix) {
        this.prefix = prefix;
    }
    
    public char getPrefix() {
        return prefix;
    }
    
    public static RespType fromPrefix(char prefix) {
        for (RespType type : values()) {
            if (type.prefix == prefix) {
                return type;
            }
        }
        return null;
    }
}
