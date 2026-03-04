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
        options.addOption("d", "duration", true, "Duration in seconds (default: 0, means use requests)");
        options.addOption("s", "size", true, "Data size in bytes (default: 100)");
        options.addOption("c", "cases", true, "Benchmark cases (default: all)");
        options.addOption("m", "monitor", false, "Monitor server memory");
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

            BenchmarkRunner runner = new BenchmarkRunner(config);

            // Register cases
            String casesStr = cmd.getOptionValue("c", "all");
            Set<String> selectedCases = new HashSet<>(Arrays.asList(casesStr.split(",")));
            boolean all = casesStr.equalsIgnoreCase("all");

            if (all || selectedCases.contains("set")) runner.addBenchmark(new SetBenchmark());
            if (all || selectedCases.contains("get")) runner.addBenchmark(new GetBenchmark());
            if (all || selectedCases.contains("incr")) runner.addBenchmark(new IncrBenchmark());
            if (all || selectedCases.contains("lpush")) runner.addBenchmark(new ListPushBenchmark());
            if (all || selectedCases.contains("lrange")) runner.addBenchmark(new ListRangeBenchmark());
            if (all || selectedCases.contains("hset")) runner.addBenchmark(new HashSetBenchmark());
            if (all || selectedCases.contains("hget")) runner.addBenchmark(new HashGetBenchmark());
            if (all || selectedCases.contains("sadd")) runner.addBenchmark(new SetAddBenchmark());

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
    }

    private static String parseMemoryInfo(String info) {
        // Simple parser to extract used_memory_human or similar
        // This depends on what LubanRDS returns for INFO MEMORY
        // Assuming standard Redis format
        String[] lines = info.split("\r\n");
        for (String line : lines) {
            if (line.startsWith("used_memory_human:")) {
                return line;
            }
        }
        return "Memory info not found";
    }
}
