package com.swarmcron.config;

import com.swarmcron.util.Json;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Loads the jobs.json seed file. Only consulted on first boot (see README). */
public final class JobsFile {

    private JobsFile() {}

    @SuppressWarnings("unchecked")
    public static List<JobSpec> load(Path path) throws IOException {
        if (!Files.exists(path)) {
            return List.of();
        }
        String text = Files.readString(path);
        Object parsed = Json.parse(text);
        if (!(parsed instanceof List<?> list)) {
            throw new IllegalArgumentException("jobs.json must contain a top-level JSON array");
        }
        List<JobSpec> specs = new ArrayList<>();
        for (Object o : list) {
            specs.add(JobSpec.fromJson((Map<String, Object>) o));
        }
        return List.copyOf(specs);
    }
}
