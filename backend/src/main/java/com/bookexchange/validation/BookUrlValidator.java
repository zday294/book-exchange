package com.bookexchange.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import org.springframework.stereotype.Component;

@Component
public class BookUrlValidator implements ConstraintValidator<BookUrl, String> {

    private final BookLinkProperties properties;

    public BookUrlValidator(BookLinkProperties properties) {
        this.properties = properties;
    }

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        // Missing/blank values are reported by @NotBlank, so don't add a second error.
        return value == null || value.isBlank() || isAllowed(value);
    }

    public boolean isAllowed(String value) {
        URI uri;
        try {
            uri = new URI(value);
        } catch (URISyntaxException e) {
            return false;
        }
        if (!"https".equals(uri.getScheme()) || uri.getUserInfo() != null) {
            return false;
        }
        if (uri.getPort() != -1 && uri.getPort() != 443) {
            return false;
        }
        String host = uri.getHost();
        if (host == null) {
            return false;
        }
        String normalized = host.toLowerCase(Locale.ROOT);
        return properties.allowedHosts().stream()
                .map(h -> h.toLowerCase(Locale.ROOT))
                .anyMatch(allowed -> normalized.equals(allowed) || normalized.endsWith("." + allowed));
    }
}
