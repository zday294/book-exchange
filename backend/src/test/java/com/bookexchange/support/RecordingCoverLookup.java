package com.bookexchange.support;

import com.bookexchange.cover.CoverLookup;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/** Replaces the real cover lookup in tests: records calls and can be told to fail. */
@TestConfiguration(proxyBeanMethods = false)
public class RecordingCoverLookup {

    public static final List<Long> CALLS = new CopyOnWriteArrayList<>();
    public static final AtomicBoolean FAIL = new AtomicBoolean(false);

    @Bean
    @Primary
    CoverLookup recordingCoverLookup() {
        return bookId -> {
            CALLS.add(bookId);
            if (FAIL.get()) {
                throw new IllegalStateException("cover service down");
            }
        };
    }
}
