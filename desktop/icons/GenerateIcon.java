/*
 * Regenerates latch.ico, the icon jpackage burns into Latch.exe and uses for the
 * Start menu / desktop shortcuts the MSI creates, along with latch.png (Linux)
 * and latch.icns (the macOS app bundle).
 *
 * Run from this directory with the JDK's single-file launcher -- no build step,
 * no dependency, and nothing to wire into Gradle:
 *
 *     java GenerateIcon.java
 *
 * The path data below is the same Latch mark vendored in LatchIcon.kt, so the
 * shortcut icon, the taskbar icon and the tray icon cannot drift apart. If the
 * mark ever changes, update both files and re-run this.
 *
 * Written by hand rather than pulled from a converter because ImageIO cannot
 * write .ico: entries at 64px and below are 32-bit DIBs, larger ones are PNG.
 * That split is what every Windows icon compiler does -- pre-Vista shells only
 * understand the DIB form, and a 256x256 DIB would add ~256 KB to the exe for
 * nothing.
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
import javax.imageio.ImageIO;

public final class GenerateIcon {

    /** Brand red, the AppTitleColor the in-app logo and the latched tray icon use. */
    private static final Color BRAND = new Color(0xC0, 0x12, 0x21);

    /**
     * Fraction of each side left empty around the mark. Windows draws icons
     * edge to edge, so without this the shortcut sits visibly larger than its
     * neighbours in the Start menu.
     */
    private static final double MARGIN = 0.06;

    /**
     * The sizes Windows actually asks for: 16 in the title bar, 20/24 in the
     * taskbar and tray overflow at the usual scale factors, 32 on the desktop,
     * 48 in Explorer's medium view, 256 for the extra-large view and the
     * Alt-Tab switcher on HiDPI.
     */
    private static final int[] SIZES = { 16, 20, 24, 32, 40, 48, 64, 128, 256 };

    /** Above this an entry is stored as PNG rather than a DIB. */
    private static final int PNG_THRESHOLD = 64;

    /** Vendored from composeResources/drawable/ic_latch.xml (viewport 191x140). */
    private static final String[] MARK = {
        "M69.54,92.49L88.83,110.95L69.07,129.18C62.04,135.36 53.07,135.48 45.07,132.96C42.3,132.09 39.88,130.39 37.78,128.38C26.66,117.72 21.93,112.41 12.83,101.92C9.57,98.15 6.42,94.21 4.26,89.71C-0.43,79.92 -1.02,70.66 1.37,56.2C2.31,50.52 4.14,44.99 7.26,40.16C12.92,31.39 21.31,22.15 36.22,7.06C37.88,5.38 39.76,3.9 41.89,2.88C51.42,-1.67 57.69,-0.61 68.13,4.53L112.1,46.9C113.12,47.88 113.96,49.05 114.51,50.36C120.19,63.79 118.22,70.78 113.64,81.9C97.53,66.53 72.25,43.68 72.25,43.68C69.62,42.1 54.96,37.46 47.2,45.92C39.44,54.38 32.58,77.36 49.2,90.49C55.53,95.45 60.1,95.56 69.54,92.49Z",
        "M58.21,106.54C68.43,112.86 74.92,113.48 86.55,108.66L78.2,100.6C73.39,95.96 69.57,92.3 69.49,92.51C62.88,94.73 59.83,94.73 54.45,93.13C50.2,91.42 48.22,89.89 45.27,86.31L41.98,82.08L44.1,86.67C47.64,95.16 50.56,99.56 58.21,106.54Z",
        "M60.09,41.16C50.8,42.3 46.96,44.31 42.33,53.63C44.24,47.69 45.87,44.39 49.98,38.57C57.74,27.9 62.19,24.44 70.32,23.87C80.86,22.61 86.64,24.58 96.78,31.99L113.24,47.98C119.36,59.31 119.96,67.8 113.63,81.95L80.43,51.15C72.72,43.74 68.2,41.31 60.09,41.16Z",
        "M121.46,46.91L102.17,28.45L121.93,10.22C130.19,2.95 141.13,4.06 150.03,7.98C164.43,21.62 168.85,26.72 180.26,39.9L180.71,40.42C182.29,42.23 183.75,44.15 184.94,46.24C191.56,57.95 192.39,68.01 189.2,85.64C188.47,89.68 187.17,93.62 185.05,97.14C179.65,106.11 171.69,115.14 157.03,130.05C153.88,133.25 150.36,136.19 146.17,137.78C140.34,139.98 135.56,139.86 129.76,137.84C125.06,136.2 121.04,133.11 117.46,129.65L78.9,92.49C77.88,91.51 77.04,90.34 76.49,89.04C70.81,75.61 72.78,68.61 77.36,57.49C93.47,72.86 118.75,95.71 118.75,95.71C121.38,97.29 136.04,101.94 143.8,93.47C151.56,85.01 158.42,62.04 141.8,48.91C135.47,43.95 130.9,43.83 121.46,46.91Z",
        "M104.52,30.74L121.4,46.99C128.01,44.77 131.36,44.8 136.74,46.4C140.99,48.12 142.78,49.51 145.73,53.08L149.02,57.31L146.9,52.73C143.36,44.23 140.44,39.83 132.79,32.85C122.57,26.53 116.15,25.91 104.52,30.74Z",
        "M130.89,98.38C140.18,97.24 144.02,94.99 148.64,85.68C146.73,91.62 145.11,94.91 141,100.73C133.24,111.4 128.79,114.87 120.65,115.43C110.12,116.69 104.33,114.72 94.2,107.32L77.73,91.33C70.75,80.47 71.39,71.39 77.15,57.34L110.67,88.28C118.38,95.7 122.77,98.23 130.89,98.38Z",
    };

    public static void main(String[] args) throws IOException {
        List<Path2D.Double> paths = new ArrayList<>();
        for (String data : MARK) {
            paths.add(parse(data));
        }

        // Measured rather than taken from the declared viewport: two subpaths run
        // a fraction past it, and letterboxing off the nominal 191x140 would clip.
        Rectangle2D bounds = null;
        for (Path2D.Double p : paths) {
            bounds = bounds == null ? p.getBounds2D() : bounds.createUnion(p.getBounds2D());
        }

        List<byte[]> payloads = new ArrayList<>();
        List<Boolean> isPng = new ArrayList<>();
        for (int size : SIZES) {
            BufferedImage image = render(paths, bounds, size);
            boolean png = size > PNG_THRESHOLD;
            payloads.add(png ? toPng(image) : toDib(image));
            isPng.add(png);
        }

        Path out = Path.of("latch.ico");
        Files.write(out, assemble(payloads));
        Path outPng = Path.of("latch.png");
        Files.write(outPng, payloads.get(SIZES.length - 1));
        System.out.println("Wrote " + out.toAbsolutePath() + " ("
            + Files.size(out) + " bytes, " + SIZES.length + " entries)");
        System.out.println("Wrote " + outPng.toAbsolutePath() + " (" + Files.size(outPng) + " bytes)");
        Path outIcns = Path.of("latch.icns");
        Files.write(outIcns, icns(paths, bounds));
        System.out.println("Wrote " + outIcns.toAbsolutePath() + " (" + Files.size(outIcns) + " bytes, "
            + ICNS_SIZES.length + " entries)");
        for (int i = 0; i < SIZES.length; i++) {
            System.out.println("  " + SIZES[i] + "x" + SIZES[i] + "  "
                + (isPng.get(i) ? "PNG " : "DIB ") + payloads.get(i).length + " bytes");
        }
    }

    /** Fits the wide mark into a square canvas, centred, without stretching it. */
    private static BufferedImage render(List<Path2D.Double> paths, Rectangle2D bounds, int size) {
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

        g.setColor(BRAND);
        // Filled subpath by subpath, as the source vector does. Merging them into
        // one shape would let non-zero winding turn the overlaps between the two
        // hooks into holes.
        for (Path2D.Double p : paths) {
            g.fill(p);
        }
        g.dispose();
        return image;
    }

    /**
     * Enough of an SVG path parser for this mark: absolute M, L, C and Z, which
     * is all Android's vector exporter emitted here. Anything else is a hard
     * error rather than a silently wrong icon.
     */
    private static Path2D.Double parse(String data) {
        Path2D.Double path = new Path2D.Double(Path2D.WIND_NON_ZERO);
        int i = 0;
        char command = 0;
        double startX = 0, startY = 0, x = 0, y = 0;

        while (i < data.length()) {
            char c = data.charAt(i);
            if (c == ' ' || c == ',' || c == '\n' || c == '\r' || c == '\t') {
                i++;
                continue;
            }
            if (Character.isLetter(c)) {
                command = c;
                i++;
                if (command == 'Z' || command == 'z') {
                    path.closePath();
                    x = startX;
                    y = startY;
                }
                continue;
            }

            // A bare number repeats the previous command, except after a moveto,
            // where SVG says the repeat is an implicit lineto.
            switch (command) {
                case 'M' -> {
                    double[] n = numbers(data, i, 2);
                    x = n[0];
                    y = n[1];
                    startX = x;
                    startY = y;
                    path.moveTo(x, y);
                    command = 'L';
                }
                case 'L' -> {
                    double[] n = numbers(data, i, 2);
                    x = n[0];
                    y = n[1];
                    path.lineTo(x, y);
                }
                case 'C' -> {
                    double[] n = numbers(data, i, 6);
                    path.curveTo(n[0], n[1], n[2], n[3], n[4], n[5]);
                    x = n[4];
                    y = n[5];
                }
                default -> throw new IllegalArgumentException(
                    "Unsupported path command '" + command + "' at offset " + i);
            }
            i = cursor;
        }
        return path;
    }

    /** Where {@link #numbers} stopped reading. */
    private static int cursor;

    private static double[] numbers(String data, int from, int count) {
        double[] out = new double[count];
        int i = from;
        for (int k = 0; k < count; k++) {
            while (i < data.length() && (data.charAt(i) == ' ' || data.charAt(i) == ',')) {
                i++;
            }
            int start = i;
            if (i < data.length() && (data.charAt(i) == '-' || data.charAt(i) == '+')) {
                i++;
            }
            while (i < data.length()
                && (Character.isDigit(data.charAt(i)) || data.charAt(i) == '.')) {
                i++;
            }
            if (start == i) {
                throw new IllegalArgumentException("Expected a number at offset " + start);
            }
            out[k] = Double.parseDouble(data.substring(start, i));
        }
        cursor = i;
        return out;
    }

    /** The .icns entry types macOS reads PNG data from, and the pixel size of each. */
    private static final String[] ICNS_TYPES = { "icp4", "icp5", "icp6", "ic07", "ic08", "ic09", "ic10" };
    private static final int[] ICNS_SIZES = { 16, 32, 64, 128, 256, 512, 1024 };

    /**
     * Apple's .icns container: the magic "icns" and the total length, then one
     * entry per size -- a four-character type, a length that counts its own
     * 8-byte header, and a PNG. Lengths are big-endian, ByteBuffer's default.
     */
    private static byte[] icns(List<Path2D.Double> paths, Rectangle2D bounds) throws IOException {
        ByteArrayOutputStream entries = new ByteArrayOutputStream();
        for (int i = 0; i < ICNS_TYPES.length; i++) {
            byte[] png = toPng(render(paths, bounds, ICNS_SIZES[i]));
            entries.write(ICNS_TYPES[i].getBytes(StandardCharsets.US_ASCII));
            entries.write(ByteBuffer.allocate(4).putInt(8 + png.length).array());
            entries.write(png);
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write("icns".getBytes(StandardCharsets.US_ASCII));
        out.write(ByteBuffer.allocate(4).putInt(8 + entries.size()).array());
        entries.writeTo(out);
        return out.toByteArray();
    }

    private static byte[] toPng(BufferedImage image) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    /**
     * A 32-bit BGRA bottom-up DIB: BITMAPINFOHEADER, the colour rows, then the
     * 1bpp AND mask. The mask is redundant next to an alpha channel but the
     * format requires it and some shells still consult it, so it is filled in
     * properly rather than zeroed.
     */
    private static byte[] toDib(BufferedImage image) {
        int w = image.getWidth();
        int h = image.getHeight();
        int maskStride = ((w + 31) / 32) * 4;
        int maskSize = maskStride * h;

        ByteBuffer buf = ByteBuffer.allocate(40 + w * h * 4 + maskSize)
            .order(ByteOrder.LITTLE_ENDIAN);

        buf.putInt(40);          // biSize
        buf.putInt(w);           // biWidth
        buf.putInt(h * 2);       // biHeight -- colour rows plus mask rows
        buf.putShort((short) 1); // biPlanes
        buf.putShort((short) 32);// biBitCount
        buf.putInt(0);           // biCompression = BI_RGB
        buf.putInt(w * h * 4 + maskSize); // biSizeImage
        buf.putInt(0);           // biXPelsPerMeter
        buf.putInt(0);           // biYPelsPerMeter
        buf.putInt(0);           // biClrUsed
        buf.putInt(0);           // biClrImportant

        for (int row = h - 1; row >= 0; row--) {
            for (int col = 0; col < w; col++) {
                int argb = image.getRGB(col, row);
                buf.put((byte) (argb));        // blue
                buf.put((byte) (argb >>> 8));  // green
                buf.put((byte) (argb >>> 16)); // red
                buf.put((byte) (argb >>> 24)); // alpha
            }
        }

        for (int row = h - 1; row >= 0; row--) {
            byte[] maskRow = new byte[maskStride];
            for (int col = 0; col < w; col++) {
                // Set means "transparent" in an AND mask.
                if ((image.getRGB(col, row) >>> 24) == 0) {
                    maskRow[col / 8] |= (byte) (0x80 >>> (col % 8));
                }
            }
            buf.put(maskRow);
        }
        return buf.array();
    }

    private static byte[] assemble(List<byte[]> payloads) {
        int directory = 6 + payloads.size() * 16;
        int total = directory;
        for (byte[] p : payloads) {
            total += p.length;
        }

        ByteBuffer buf = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN);
        buf.putShort((short) 0); // reserved
        buf.putShort((short) 1); // type: icon
        buf.putShort((short) payloads.size());

        int offset = directory;
        for (int i = 0; i < payloads.size(); i++) {
            int size = SIZES[i];
            // 256 is stored as 0 -- the field is a single byte.
            buf.put((byte) (size == 256 ? 0 : size)); // width
            buf.put((byte) (size == 256 ? 0 : size)); // height
            buf.put((byte) 0);        // palette entries: none, it is direct colour
            buf.put((byte) 0);        // reserved
            buf.putShort((short) 1);  // colour planes
            buf.putShort((short) 32); // bits per pixel
            buf.putInt(payloads.get(i).length);
            buf.putInt(offset);
            offset += payloads.get(i).length;
        }
        for (byte[] p : payloads) {
            buf.put(p);
        }
        return buf.array();
    }

    private GenerateIcon() {}
}
