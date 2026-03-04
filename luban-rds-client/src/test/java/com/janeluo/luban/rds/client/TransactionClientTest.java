package com.janeluo.luban.rds.client;

public class TransactionClientTest {
    public static void main(String[] args) throws Exception {
        NettyRedisClient c1 = new NettyRedisClient("localhost", 9736);
        NettyRedisClient c2 = new NettyRedisClient("localhost", 9736);
        c1.connect();
        c2.connect();
        try {
            Object r1 = c1.executeCommand("MULTI");
            System.out.println("MULTI -> " + r1);
            Object q1 = c1.executeCommand("SET", "tx:key", "1");
            System.out.println("SET QUEUED -> " + q1);
            Object q2 = c1.executeCommand("INCR", "tx:key");
            System.out.println("INCR QUEUED -> " + q2);
            Object exec1 = c1.executeCommand("EXEC");
            System.out.println("EXEC -> " + exec1);
            Object val1 = c1.executeCommand("GET", "tx:key");
            System.out.println("GET -> " + val1);
            
            Object w = c1.executeCommand("WATCH", "watch:key");
            System.out.println("WATCH -> " + w);
            Object m = c1.executeCommand("MULTI");
            System.out.println("MULTI -> " + m);
            Object q3 = c1.executeCommand("SET", "watch:key", "a");
            System.out.println("SET QUEUED -> " + q3);
            Object other = c2.executeCommand("SET", "watch:key", "b");
            System.out.println("other SET -> " + other);
            Object exec2 = c1.executeCommand("EXEC");
            System.out.println("EXEC after change -> " + exec2);
        } finally {
            if (c1.isConnected()) c1.disconnect();
            if (c2.isConnected()) c2.disconnect();
        }
    }
}
