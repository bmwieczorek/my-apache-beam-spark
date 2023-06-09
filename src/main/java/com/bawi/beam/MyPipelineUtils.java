package com.bawi.beam;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public class MyPipelineUtils {
    private static final Logger LOGGER = LoggerFactory.getLogger(MyPipelineUtils.class);

    public static String[] updateArgsAndAutodetectRunnerIfLocal(String[] args, String... additionalArgs) {
        if (!isLocal()) {
            return args;
        }
        Set<String> merged = new LinkedHashSet<>();
        merged.addAll(Arrays.asList(args));
        merged.addAll(Arrays.asList(additionalArgs));
        Map<String, String> map = merged.stream().collect(
                Collectors.toMap(s -> s.substring(0, s.indexOf("=")), s -> s.substring(s.indexOf("=") + 1), (s1, s2) -> s1));
        if (!map.containsKey("--runner")) {
            try {
                Class.forName("org.apache.beam.runners.spark.SparkRunner");
                LOGGER.info("No runner specified by --runner argument. Using SparkRunner as detected on class path");
                map.put("--runner", "SparkRunner"); // --runner=SparkRunner
            } catch (ClassNotFoundException e) {
                // ignore
            }
        }
        String[] strings = map.entrySet().stream().map(e -> e.getKey() + "=" + e.getValue()).toArray(String[]::new);
        LOGGER.info("Merged args={}", map);
        return strings;

    }

    public static boolean isLocal() {
        String osName = System.getProperty("os.name").toLowerCase();
        boolean isLocal = osName.contains("mac") || osName.contains("windows");
       LOGGER.info("Is system local: " + isLocal);
        return isLocal;
    }
}
