package com.bookexchange.config;

import com.bookexchange.book.WrappingPapers;
import com.bookexchange.validation.AmazonProperties;
import com.bookexchange.validation.AmazonUrl;
import com.bookexchange.validation.WrappingPaper;
import io.swagger.v3.core.converter.AnnotatedType;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.Schema;
import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.stream.Stream;
import org.springdoc.core.customizers.PropertyCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** API metadata, plus schema details that Bean Validation annotations alone can't express. */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI bookExchangeOpenApi() {
        return new OpenAPI().info(new Info()
                .title("Christmas Book Exchange API")
                .version("v1")
                .description("""
                        Submit wrapped books to a Christmas book exchange and claim one from the pile.

                        All errors use the same body: `{ "error": "...", "details": [ ... ] }`.
                        Identity is name-only: endpoints that need to know who is asking read the optional
                        `X-Participant-Name` header."""));
    }

    /** Lists the real allowed values for the custom constraints, so docs never drift from validation. */
    @Bean
    public PropertyCustomizer customConstraintDocs(WrappingPapers papers, AmazonProperties amazon) {
        return (Schema property, AnnotatedType type) -> {
            Annotation[] annotations = type.getCtxAnnotations();
            if (annotations == null) {
                return property;
            }
            if (Stream.of(annotations).anyMatch(a -> a instanceof WrappingPaper)) {
                property.setEnum(new ArrayList<>(papers.keys()));
            }
            if (Stream.of(annotations).anyMatch(a -> a instanceof AmazonUrl)) {
                property.setFormat("uri");
                property.setDescription("An https link to Amazon. Allowed hosts: "
                        + String.join(", ", amazon.allowedHosts())
                        + " (subdomains such as www.amazon.com are allowed too).");
            }
            return property;
        };
    }
}
