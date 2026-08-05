package com.janeluo.luban.rds.benchmark;

import com.janeluo.luban.rds.benchmark.api.BenchmarkConfig;
import com.janeluo.luban.rds.benchmark.cases.*;
import com.janeluo.luban.rds.benchmark.core.BenchmarkRunner;
import org.apache.commons.cli.*;
import redis.clients.jedis.Jedis;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class LubanBenchmarkMain {

    public static void main(String[] args) {
        Options options = new Options();
        options.addOption("h", "host", true, "Server host (default: 127.0.0.1)");
        options.addOption("p", "port", true, "Server port (default: 9736)");
        options.addOption("t", "threads", true, "Number of threads (default: 10)");
        options.addOption("n", "requests", true, "Total requests (default: 100000)");
        options.addOption("d", "duration", true, "Duration in seconds (default: 0, use total requests)");
        options.addOption("s", "size", true, "Value size in bytes (default: 100)");
        options.addOption("c", "cases", true, "Benchmark cases: all,set,get,incr,lpush,lrange,hset,hget,sadd,large-set,large-get (default: all)");
        options.addOption("m", "memory", false, "Monitor memory usage");
        options.addOption("pipeline", true, "Pipeline batch size (default: 1, no pipeline)");
        options.addOption("pool", true, "Connection pool size (default: 0, one connection per thread)");
        options.addOption("help", false, "Print help");

        CommandLineParser parser = new DefaultParser();
        try {
            CommandLine cmd = parser.parse(options, args);
            if (cmd.hasOption("help")) {
                printHelp(options);
                return;
            }

            BenchmarkConfig config = new BenchmarkConfig();
            config.setHost(cmd.getOptionValue("h", "127.0.0.1"));
            config.setPort(Integer.parseInt(cmd.getOptionValue("p", "9736")));
            config.setThreads(Integer.parseInt(cmd.getOptionValue("t", "10")));
            config.setTotalOperations(Integer.parseInt(cmd.getOptionValue("n", "100000")));
            config.setDurationSeconds(Integer.parseInt(cmd.getOptionValue("d", "0")));
            config.setValueSize(Integer.parseInt(cmd.getOptionValue("s", "100")));
            config.setMonitorMemory(cmd.hasOption("m"));
            config.setPipelineBatchSize(Integer.parseInt(cmd.getOptionValue("pipeline", "1")));
            config.setConnectionPoolSize(Integer.parseInt(cmd.getOptionValue("pool", "0")));

            String casesStr = cmd.getOptionValue("c", "all");
            Set<String> selectedCases = new HashSet<>(Arrays.asList(casesStr.toLowerCase().split(",")));
            boolean all = casesStr.equalsIgnoreCase("all");

            BenchmarkRunner runner = new BenchmarkRunner(config);
            if (all || selectedCases.contains("set")) runner.addBenchmark(new SetBenchmark());
            if (all || selectedCases.contains("get")) runner.addBenchmark(new GetBenchmark());
            if (all || selectedCases.contains("incr")) runner.addBenchmark(new IncrBenchmark());
            if (all || selectedCases.contains("lpush")) runner.addBenchmark(new ListPushBenchmark());
            if (all || selectedCases.contains("lrange")) runner.addBenchmark(new ListRangeBenchmark());
            if (all || selectedCases.contains("hset")) runner.addBenchmark(new HashSetBenchmark());
            if (all || selectedCases.contains("hget")) runner.addBenchmark(new HashGetBenchmark());
            if (all || selectedCases.contains("sadd")) runner.addBenchmark(new SetAddBenchmark());
            if (all || selectedCases.contains("large-set")) runner.addBenchmark(new LargeValueSetBenchmark());
            if (all || selectedCases.contains("large-get")) runner.addBenchmark(new LargeValueGetBenchmark());

            // Print configuration info
            System.out.println("Starting Benchmark Suite...");
            System.out.println(config);
            if (config.isPipelineEnabled()) {
                System.out.println("Pipeline mode enabled with batch size: " + config.getPipelineBatchSize());
            }
            System.out.println("===================================================================================");

            // Memory Monitoring
            ScheduledExecutorService monitorService = null;
            if (config.isMonitorMemory()) {
                monitorService = Executors.newSingleThreadScheduledExecutor();
                monitorService.scheduleAtFixedRate(() -> {
                    try (Jedis jedis = new Jedis(config.getHost(), config.getPort(), 2000)) {
                        String info = jedis.info("memory");
                        System.out.println("[Memory Monitor] " + parseMemoryInfo(info));
                    } catch (Exception e) {
                        System.err.println("[Memory Monitor] Failed to get memory info: " + e.getMessage());
                    }
                }, 1, 5, TimeUnit.SECONDS);
            }

            try {
                runner.run();
            } finally {
                if (monitorService != null) {
                    monitorService.shutdown();
                }
            }

        } catch (ParseException e) {
            System.err.println("Error parsing arguments: " + e.getMessage());
            printHelp(options);
        } catch (NumberFormatException e) {
            System.err.println("Invalid number format: " + e.getMessage());
        }
    }

    private static void printHelp(Options options) {
        HelpFormatter formatter = new HelpFormatter();
        formatter.printHelp("LubanBenchmark", options);
        System.out.println("\nExamples:");
        System.out.println("  # Basic benchmark with 50 threads");
        System.out.println("  java -jar benchmark.jar -t 50 -n 500000");
        System.out.println("");
        System.out.println("  # Pipeline mode with batch size 100");
        System.out.println("  java -jar benchmark.jar -t 50 -n 500000 --pipeline 100");
        System.out.println("");
        System.out.println("  # Connection pool mode");
        System.out.println("  java -jar benchmark.jar -t 100 --pool 50");
        System.out.println("");
        System.out.println("  # Specific benchmarks only");
        System.out.println("  java -jar benchmark.jar -c set,get -t 50");
    }

    private static String parseMemoryInfo(String info) {
        String[] lines = info.split("\r\n");
        for (String line : lines) {
            if (line.startsWith("used_memory_human:")) {
                return line;
            }
        }
        return "Memory info not found";
    }
}
