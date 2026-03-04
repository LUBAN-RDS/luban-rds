package com.janeluo.luban.rds.protocol;

public class Command {
    private final String name;
    private final String[] args;
    
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
