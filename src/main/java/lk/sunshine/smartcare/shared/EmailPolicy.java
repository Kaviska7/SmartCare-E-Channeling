package lk.sunshine.smartcare.shared;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.Locale;
import java.util.regex.Pattern;

public final class EmailPolicy {
    private static final Pattern GMAIL = Pattern.compile("^[^@\\s]+@gmail\\.com$", Pattern.CASE_INSENSITIVE);
    private static final String MESSAGE = "Email must be a valid @gmail.com address";

    private EmailPolicy() {}

    public static String newAddress(String value) {
        String normalized = normalize(value);
        requireGmail(normalized);
        return normalized;
    }

    public static String changedAddress(String value, String currentValue) {
        String normalized = normalize(value);
        String current = normalize(currentValue);
        if (!normalized.equals(current)) requireGmail(normalized);
        return normalized;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static void requireGmail(String email) {
        if (!GMAIL.matcher(email).matches())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, MESSAGE);
    }
}
