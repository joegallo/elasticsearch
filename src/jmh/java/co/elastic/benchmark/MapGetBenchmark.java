package co.elastic.benchmark;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

import org.openjdk.jmh.annotations.*;

@State(Scope.Thread)
public class MapGetBenchmark {

    @Param({"16", "32", "64", "128", "256"})
    int size;

    @Param({"hit", "miss"})
    String mode;

    private Map<String, String> hashMap;
    private Map<String, String> hashMap_lf050;
    private Map<String, String> hashMap_lf025;
    private Map<String, String> mapCopyOf;
    private Map<String, String> unmodifiableMap;

    private String[] lookupKeys;

    @Setup(Level.Trial)
    public void setup() {
        HashMap<String, String> base = new HashMap<>();
        for (int i = 0; i < size; i++) {
            base.put("key" + i, "value" + i);
        }
        if ("hit".equals(mode)) {
            lookupKeys = base.keySet().toArray(String[]::new);
        } else {
            lookupKeys = new String[size];
            for (int i = 0; i < size; i++) {
                lookupKeys[i] = "miss" + i;
            }
        }

        hashMap = new HashMap<>(base);

        hashMap_lf050 = new HashMap<>(16, 0.50f);
        hashMap_lf050.putAll(base);

        hashMap_lf025 = new HashMap<>(16, 0.25f);
        hashMap_lf025.putAll(base);

        mapCopyOf = Map.copyOf(base);
        unmodifiableMap = Collections.unmodifiableMap(new HashMap<>(base));
    }

    private String randomKey() {
        return lookupKeys[ThreadLocalRandom.current().nextInt(lookupKeys.length)];
    }

    @Benchmark
    public String hashMap() {
        return hashMap.get(randomKey());
    }

    @Benchmark
    public String hashMap_lf050() {
        return hashMap_lf050.get(randomKey());
    }

    @Benchmark
    public String hashMap_lf025() {
        return hashMap_lf025.get(randomKey());
    }

    @Benchmark
    public String mapCopyOf() {
        return mapCopyOf.get(randomKey());
    }

    @Benchmark
    public String unmodifiableMap() {
        return unmodifiableMap.get(randomKey());
    }
}
