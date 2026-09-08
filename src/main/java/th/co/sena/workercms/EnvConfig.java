package th.co.sena.workercms;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class EnvConfig {
    private final Map<String, String> fileValues;

    public EnvConfig() {
        this.fileValues = load(Path.of(".env"));
    }

    EnvConfig(Map<String, String> values) {
        this.fileValues = Map.copyOf(values);
    }

    private static Map<String, String> load(Path path) {
        Map<String, String> values = new LinkedHashMap<>();
        if (!Files.isRegularFile(path)) return values;
        try {
            for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
                String clean = line.strip();
                if (clean.isEmpty() || clean.startsWith("#") || !clean.contains("=")) continue;
                int separator = clean.indexOf('=');
                String value = clean.substring(separator + 1).strip();
                if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
                    value = value.substring(1, value.length() - 1);
                }
                values.put(clean.substring(0, separator).strip(), value);
            }
            return values;
        } catch (IOException exception) {
            throw new IllegalStateException("อ่านไฟล์ .env ไม่สำเร็จ", exception);
        }
    }

    public String get(String key, String fallback) {
        String system = System.getenv(key);
        if (system != null && !system.isBlank()) return system;
        return fileValues.getOrDefault(key, fallback);
    }
    public int getInt(String key, int fallback) {
        try { return Integer.parseInt(get(key, String.valueOf(fallback))); }
        catch (NumberFormatException exception) { return fallback; }
    }
    public double getDouble(String key, double fallback) {
        try { return Double.parseDouble(get(key, String.valueOf(fallback))); }
        catch (NumberFormatException exception) { return fallback; }
    }
    public boolean getBoolean(String key, boolean fallback) {
        return Boolean.parseBoolean(get(key, String.valueOf(fallback)));
    }
    public boolean configured() {
        return !get("CMS_USERNAME", "").isBlank() && !get("CMS_PASSWORD", "").isBlank();
    }
}
