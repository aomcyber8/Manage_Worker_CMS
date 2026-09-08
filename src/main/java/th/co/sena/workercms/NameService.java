package th.co.sena.workercms;

import java.util.Locale;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

@Service
public class NameService {
    private static final Pattern PREFIX = Pattern.compile("^(?:นางสาว|น\\.ส\\.|นาย|นาง)\\s*");

    public String normalize(String value) {
        String compact = value == null ? "" : value.strip().replaceAll("\\s+", " ");
        return PREFIX.matcher(compact).replaceFirst("").strip();
    }
    public String key(String value) { return normalize(value).toLowerCase(Locale.ROOT); }
    public String surname(String value) {
        String normalized = normalize(value);
        int separator = normalized.indexOf(' ');
        return separator < 0 ? "" : normalized.substring(separator + 1);
    }
}
