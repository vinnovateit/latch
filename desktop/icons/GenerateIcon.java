/*
 * Rasterises icons/latch.svg with the build JDK alone -- rsvg-convert and
 * ImageMagick are not available on Windows. Run in source-file mode:
 *
 *     java GenerateIcon.java <svg> <output-dir>
 *
 * Only the SVG subset this mark uses is implemented: absolute M/L/C/Z with
 * 6-digit hex fills. Anything else is a hard error rather than a wrong icon.
 */

import java.awt.Color;
import java.awt.RenderingHints;
import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.imageio.ImageIO;

public final class GenerateIcon {

    private record Shape(Path2D.Double path, Color fill) {}

    private static final double MARGIN = 0.06;
    private static final int[] PNG_SIZES = { 32, 64, 128, 256, 512 };
    /** Sizes Windows asks for in the MSI shortcut and Add/Remove Programs icon. */
    private static final int[] ICO_SIZES = { 16, 20, 24, 32, 40, 48, 64, 128, 256 };
    private static final int PNG_THRESHOLD = 64;
    private static final Pattern PATH_TAG = Pattern.compile("<path\\b[^>]*>", Pattern.DOTALL);
    private static final Pattern PATH_DATA = Pattern.compile("\\bd=\"([^\"]+)\"");
    private static final Pattern PATH_FILL = Pattern.compile("\\bfill=\"#([0-9a-fA-F]{6})\"");
    private static int cursor;

    public static void main(String[] args) throws IOException {
        if (args.length < 2) throw new IllegalArgumentException("usage: java GenerateIcon.java <svg> <output-dir>");
        Path svg = Path.of(args[0]);
        Path outDir = Path.of(args[1]);
        Files.createDirectories(outDir);

        List<Shape> shapes = parseSvg(Files.readString(svg));
        Rectangle2D bounds = null;
        for (Shape s : shapes) {
            bounds = bounds == null ? s.path().getBounds2D() : bounds.createUnion(s.path().getBounds2D());
        }
        for (int size : PNG_SIZES) {
            Files.write(outDir.resolve("latch-" + size + ".png"), toPng(render(shapes, bounds, size)));
        }
        Files.write(outDir.resolve("latch.ico"), ico(shapes, bounds));
        System.out.println("Generated icons from " + svg.toAbsolutePath() + " in " + outDir.toAbsolutePath());
    }

    private static List<Shape> parseSvg(String svg) {
        List<Shape> shapes = new ArrayList<>();
        Matcher tags = PATH_TAG.matcher(svg);
        while (tags.find()) {
            String tag = tags.group();
            Matcher data = PATH_DATA.matcher(tag);
            Matcher fill = PATH_FILL.matcher(tag);
            if (!data.find()) continue;
            Color color = fill.find() ? Color.decode("#" + fill.group(1)) : new Color(0xC0, 0x12, 0x21);
            shapes.add(new Shape(parsePath(data.group(1)), color));
        }
        if (shapes.isEmpty()) throw new IllegalStateException("No <path> elements found in latch.svg");
        return shapes;
    }

    private static BufferedImage render(List<Shape> shapes, Rectangle2D bounds, int size) {
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        var g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        double usable = size * (1 - 2 * MARGIN);
        double factor = Math.min(usable / bounds.getWidth(), usable / bounds.getHeight());
        g.translate(
            (size - bounds.getWidth() * factor) / 2 - bounds.getX() * factor,
            (size - bounds.getHeight() * factor) / 2 - bounds.getY() * factor);
        g.scale(factor, factor);
        for (Shape s : shapes) {
            g.setColor(s.fill());
            g.fill(s.path());
        }
        g.dispose();
        return image;
    }

    /** Absolute M, L, C and Z, with bare numbers repeating the previous command. */
    private static Path2D.Double parsePath(String data) {
        Path2D.Double path = new Path2D.Double(Path2D.WIND_NON_ZERO);
        int i = 0;
        char command = 0;
        double startX = 0, startY = 0;
        while (i < data.length()) {
            char c = data.charAt(i);
            if (Character.isWhitespace(c) || c == ',') { i++; continue; }
            if (Character.isLetter(c)) {
                command = c;
                i++;
                if (command == 'Z' || command == 'z') path.closePath();
                continue;
            }
            switch (command) {
                case 'M' -> {
                    double[] n = numbers(data, i, 2);
                    startX = n[0]; startY = n[1];
                    path.moveTo(n[0], n[1]);
                    command = 'L';
                }
                case 'L' -> {
                    double[] n = numbers(data, i, 2);
                    path.lineTo(n[0], n[1]);
                }
                case 'C' -> {
                    double[] n = numbers(data, i, 6);
                    path.curveTo(n[0], n[1], n[2], n[3], n[4], n[5]);
                }
                default -> throw new IllegalArgumentException("Unsupported path command '" + command + "'");
            }
            i = cursor;
        }
        return path;
    }

    private static double[] numbers(String data, int from, int count) {
        double[] out = new double[count];
        int i = from;
        for (int k = 0; k < count; k++) {
            while (i < data.length() && (data.charAt(i) == ' ' || data.charAt(i) == ',')) i++;
            int start = i;
            if (i < data.length() && (data.charAt(i) == '-' || data.charAt(i) == '+')) i++;
            while (i < data.length() && (Character.isDigit(data.charAt(i)) || data.charAt(i) == '.')) i++;
            if (start == i) throw new IllegalArgumentException("Expected a number at offset " + start);
            out[k] = Double.parseDouble(data.substring(start, i));
        }
        cursor = i;
        return out;
    }

    /** 32-bit BGRA bottom-up DIB plus the required 1bpp AND mask. */
    private static byte[] toDib(BufferedImage image) {
        int w = image.getWidth();
        int h = image.getHeight();
        int maskStride = ((w + 31) / 32) * 4;
        int maskSize = maskStride * h;

        ByteBuffer buf = ByteBuffer.allocate(40 + w * h * 4 + maskSize).order(ByteOrder.LITTLE_ENDIAN);
        buf.putInt(40);
        buf.putInt(w);
        buf.putInt(h * 2);
        buf.putShort((short) 1);
        buf.putShort((short) 32);
        buf.putInt(0);
        buf.putInt(w * h * 4 + maskSize);
        buf.putInt(0);
        buf.putInt(0);
        buf.putInt(0);
        buf.putInt(0);
        for (int row = h - 1; row >= 0; row--) {
            for (int col = 0; col < w; col++) {
                int argb = image.getRGB(col, row);
                buf.put((byte) argb);
                buf.put((byte) (argb >>> 8));
                buf.put((byte) (argb >>> 16));
                buf.put((byte) (argb >>> 24));
            }
        }
        for (int row = h - 1; row >= 0; row--) {
            byte[] maskRow = new byte[maskStride];
            for (int col = 0; col < w; col++) {
                if ((image.getRGB(col, row) >>> 24) == 0) maskRow[col / 8] |= (byte) (0x80 >>> (col % 8));
            }
            buf.put(maskRow);
        }
        return buf.array();
    }

    private static byte[] ico(List<Shape> shapes, Rectangle2D bounds) throws IOException {
        List<byte[]> payloads = new ArrayList<>();
        for (int size : ICO_SIZES) {
            BufferedImage image = render(shapes, bounds, size);
            payloads.add(size > PNG_THRESHOLD ? toPng(image) : toDib(image));
        }
        int directory = 6 + payloads.size() * 16;
        int total = directory;
        for (byte[] p : payloads) total += p.length;
        ByteBuffer buf = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN);
        buf.putShort((short) 0);
        buf.putShort((short) 1);
        buf.putShort((short) payloads.size());
        int offset = directory;
        for (int i = 0; i < payloads.size(); i++) {
            int size = ICO_SIZES[i];
            buf.put((byte) (size == 256 ? 0 : size));
            buf.put((byte) (size == 256 ? 0 : size));
            buf.put((byte) 0);
            buf.put((byte) 0);
            buf.putShort((short) 1);
            buf.putShort((short) 32);
            buf.putInt(payloads.get(i).length);
            buf.putInt(offset);
            offset += payloads.get(i).length;
        }
        for (byte[] p : payloads) buf.put(p);
        return buf.array();
    }

    private static byte[] toPng(BufferedImage image) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    private GenerateIcon() {}
}
