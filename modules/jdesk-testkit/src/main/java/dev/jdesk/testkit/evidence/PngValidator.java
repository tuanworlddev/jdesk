package dev.jdesk.testkit.evidence;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.HashSet;
import java.util.Set;
import javax.imageio.ImageIO;

/**
 * Snapshot validation for native evidence (spec section 17.3): the image must be
 * decodable, have the expected dimensions, not be blank, and contain non-uniform
 * pixels. ImageIO is used headlessly for decoding only — this class never creates UI
 * and lives exclusively in the test kit, outside production runtime variants.
 */
public final class PngValidator {
    /** Distinct-color floor below which a "rendered page" screenshot is implausible. */
    private static final int MIN_DISTINCT_COLORS = 8;

    private PngValidator() {
    }

    public record Result(boolean valid, int width, int height, int distinctColors, String detail) {
    }

    public static Result validate(byte[] png, int expectedMinWidth, int expectedMinHeight) {
        BufferedImage image;
        try {
            image = ImageIO.read(new ByteArrayInputStream(png));
        } catch (IOException e) {
            return new Result(false, 0, 0, 0, "not decodable: " + e.getMessage());
        }
        if (image == null) {
            return new Result(false, 0, 0, 0, "not a decodable image");
        }
        int width = image.getWidth();
        int height = image.getHeight();
        if (width < expectedMinWidth || height < expectedMinHeight) {
            return new Result(false, width, height, 0,
                    "unexpected dimensions " + width + "x" + height);
        }
        Set<Integer> colors = new HashSet<>();
        int stepX = Math.max(1, width / 64);
        int stepY = Math.max(1, height / 64);
        for (int y = 0; y < height; y += stepY) {
            for (int x = 0; x < width; x += stepX) {
                colors.add(image.getRGB(x, y));
                if (colors.size() > 256) {
                    break;
                }
            }
        }
        if (colors.size() < MIN_DISTINCT_COLORS) {
            return new Result(false, width, height, colors.size(),
                    "image is blank or near-uniform (" + colors.size() + " colors)");
        }
        return new Result(true, width, height, colors.size(), "ok");
    }
}
