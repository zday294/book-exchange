package com.bookexchange.cover;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

class ImageShapeTest {

    private static byte[] image(int w, int h, String format) throws Exception {
        var out = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB), format, out);
        return out.toByteArray();
    }

    @Test
    void widthOverHeightRoundedToTwoDecimals() throws Exception {
        assertThat(ImageShape.aspectRatio(image(200, 300, "png"))).hasValueSatisfying(r -> assertThat(r).isEqualByComparingTo("0.67"));
        assertThat(ImageShape.aspectRatio(image(300, 200, "jpg"))).hasValueSatisfying(r -> assertThat(r).isEqualByComparingTo("1.50"));
        assertThat(ImageShape.aspectRatio(image(100, 100, "gif"))).hasValueSatisfying(r -> assertThat(r).isEqualByComparingTo("1.00"));
    }

    @Test
    void unreadableOrAbsurdImagesGiveNothing() throws Exception {
        assertThat(ImageShape.aspectRatio(new byte[] {1, 2, 3})).isEmpty();
        assertThat(ImageShape.aspectRatio(new byte[0])).isEmpty();
        assertThat(ImageShape.aspectRatio(image(1, 100, "png"))).isEmpty();   // 0.01
        assertThat(ImageShape.aspectRatio(image(2000, 10, "png"))).isEmpty(); // 200
    }
}
