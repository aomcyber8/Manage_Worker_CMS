package th.co.sena.workercms;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

@Service
public class NameService {
    private static final Pattern PREFIX = Pattern.compile("^(?:นางสาว|น\\.ส\\.|นาย|นาง)\\s*");

    public String normalize(String value) {
        if (value == null) return "";
        String unicode = Normalizer.normalize(value, Normalizer.Form.NFC).replace(' ', ' ');
        String compact = unicode.replaceAll("[\s\u200B\uFEFF]+", " ").strip();
        return PREFIX.matcher(compact).replaceFirst("").strip();
    }
    public String key(String value) { return normalize(value).toLowerCase(Locale.ROOT); }
    public String surname(String value) {
        String normalized = normalize(value);
        int separator = normalized.indexOf(' ');
        return separator < 0 ? "" : normalized.substring(separator + 1);
    }
}
