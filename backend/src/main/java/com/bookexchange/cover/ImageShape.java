package com.bookexchange.cover;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Iterator;
import java.util.Optional;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;

/** Reads an image's proportions from its header (the picture itself is never decoded). */
public final class ImageShape {

    private static final BigDecimal MIN = new BigDecimal("0.10");
    private static final BigDecimal MAX = new BigDecimal("10.00");

    private ImageShape() {
    }

    /** Width divided by height, rounded to two decimals; empty if unreadable or absurd. Never exposes pixel sizes. */
    public static Optional<BigDecimal> aspectRatio(byte[] image) {
        try (ImageInputStream in = ImageIO.createImageInputStream(new ByteArrayInputStream(image))) {
            if (in == null) {
                return Optional.empty();
            }
            Iterator<ImageReader> readers = ImageIO.getImageReaders(in);
            if (!readers.hasNext()) {
                return Optional.empty();
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(in);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                if (width <= 0 || height <= 0) {
                    return Optional.empty();
                }
                BigDecimal ratio = BigDecimal.valueOf(width).divide(BigDecimal.valueOf(height), 2, RoundingMode.HALF_UP);
                return ratio.compareTo(MIN) >= 0 && ratio.compareTo(MAX) <= 0 ? Optional.of(ratio) : Optional.empty();
            } finally {
                reader.dispose();
            }
        } catch (IOException | RuntimeException e) {
            return Optional.empty();
        }
    }
}
