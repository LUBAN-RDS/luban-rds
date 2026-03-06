package com.janeluo.luban.rds.protocol;

import java.util.Arrays;

/**
 * Redis 命令对象
 * 封装命令名称和参数
 */
public class Command {

    /** 命令名称 */
    private final String name;

    /** 命令参数数组 */
    private final String[] args;

    /**
     * 构造命令对象
     *
     * @param name 命令名称
     * @param args 命令参数数组
     */
    public Command(String name, String[] args) {
        this.name = name;
        this.args = args;
    }

    public String getName() {
        return name;
    }

    public String[] getArgs() {
        return args;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("Command{name='").append(name).append("', args=[");
        for (int i = 0; i < args.length; i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append("'").append(args[i]).append("'");
        }
        sb.append("]}");
        return sb.toString();
    }
}
