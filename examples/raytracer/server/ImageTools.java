package org.babblelang.raytracer.server;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import javax.imageio.ImageIO;

/**
 * Image utilities for {@link RenderServer}: PNG encoding, multi-angle turntable contact-sheet
 * composition, and fast perceptual scoring + 4-panel diff strip matching {@code
 * examples/raytracer/assets/score_triceratops.py}.
 */
public final class ImageTools {

    private static final int FG_WHITE_THRESHOLD = 30;
    private static final ConcurrentHashMap<RefKey, CachedRef> REF_CACHE = new ConcurrentHashMap<>();

    private record RefKey(Path path, long mtimeMs, int width, int height) {}

    private record CachedRef(BufferedImage image, int[] rgb) {}

    public record Tile(String label, BufferedImage image) {}

    public record ScoreResult(
            int width,
            int height,
            double compositeScore,
            double silhouetteIoU,
            double fgColorScore,
            double ssimScore,
            double fullColorScore,
            double fgRmse,
            double fullRmse,
            double psnrDb,
            int fgTp,
            int fgFn,
            int fgFp,
            BufferedImage diffStrip) {}

    private ImageTools() {}

    public static byte[] encodePng(BufferedImage image) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream(32 * 1024);
        if (!ImageIO.write(image, "png", baos)) {
            throw new IOException("No PNG ImageIO writer available");
        }
        return baos.toByteArray();
    }

    /** Arranges one or more labeled renders into a crisp grid contact-sheet PNG. */
    public static BufferedImage composeTurntable(List<Tile> tiles) {
        if (tiles.isEmpty()) {
            throw new IllegalArgumentException("Turntable requires at least one tile");
        }
        int count = tiles.size();
        int cols = count <= 3 ? count : (count == 4 ? 2 : 3);
        int rows = (count + cols - 1) / cols;

        int tileW = 0;
        int tileH = 0;
        for (Tile t : tiles) {
            tileW = Math.max(tileW, t.image().getWidth());
            tileH = Math.max(tileH, t.image().getHeight());
        }

        int pad = 6;
        int headerH = 22;
        int cellW = tileW + pad * 2;
        int cellH = tileH + headerH + pad * 2;
        int outW = cols * cellW;
        int outH = rows * cellH;

        BufferedImage sheet = new BufferedImage(outW, outH, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = sheet.createGraphics();
        try {
            g.setRenderingHint(
                    RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setColor(new Color(24, 26, 32));
            g.fillRect(0, 0, outW, outH);
            g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 12));

            for (int i = 0; i < count; i++) {
                int col = i % cols;
                int row = i / cols;
                int x0 = col * cellW;
                int y0 = row * cellH;

                g.setColor(new Color(34, 38, 48));
                g.fillRect(x0 + 2, y0 + 2, cellW - 4, headerH);
                g.setColor(new Color(230, 236, 244));
                g.drawString(tiles.get(i).label(), x0 + pad + 2, y0 + headerH - 5);

                BufferedImage img = tiles.get(i).image();
                int ix = x0 + pad + (tileW - img.getWidth()) / 2;
                int iy = y0 + headerH + pad + (tileH - img.getHeight()) / 2;
                g.drawImage(img, ix, iy, null);

                g.setColor(new Color(52, 58, 72));
                g.drawRect(x0 + 1, y0 + 1, cellW - 3, cellH - 3);
            }
        } finally {
            g.dispose();
        }
        return sheet;
    }

    /**
     * Scores {@code rendered} against the reference image at {@code refPath}, using the exact
     * metrics of {@code score_triceratops.py} (redmean perceptual RMSE, foreground silhouette IoU,
     * 8x8 windowed SSIM, PSNR, composite score, and optional 4-panel diff strip).
     */
    public static ScoreResult scoreAgainstReference(
            Path refPath, BufferedImage rendered, boolean buildDiffStrip) throws IOException {
        int w = rendered.getWidth();
        int h = rendered.getHeight();
        CachedRef ref = loadReference(refPath, w, h);

        int n = w * h;
        int[] refRgb = ref.rgb();
        int[] renRgb = rendered.getRGB(0, 0, w, h, null, 0, w);

        double[] refLuma = new double[n];
        double[] renLuma = new double[n];

        double fullPercSq = 0.0;
        double fullRgbSq = 0.0;
        double fgPercSq = 0.0;
        int fgUnion = 0;
        int fgInter = 0;
        int fgRefOnly = 0;
        int fgRenOnly = 0;

        int[] diffPixels = buildDiffStrip ? new int[n] : null;
        int[] silPixels = buildDiffStrip ? new int[n] : null;

        for (int i = 0; i < n; i++) {
            int c1 = refRgb[i];
            int r1 = (c1 >> 16) & 0xFF;
            int g1 = (c1 >> 8) & 0xFF;
            int b1 = c1 & 0xFF;

            int c2 = renRgb[i];
            int r2 = (c2 >> 16) & 0xFF;
            int g2 = (c2 >> 8) & 0xFF;
            int b2 = c2 & 0xFF;

            refLuma[i] = 0.299 * r1 + 0.587 * g1 + 0.114 * b1;
            renLuma[i] = 0.299 * r2 + 0.587 * g2 + 0.114 * b2;

            double psq = perceptualSqDist(r1, g1, b1, r2, g2, b2);
            fullPercSq += psq;
            double dr = r1 - r2;
            double dg = g1 - g2;
            double db = b1 - b2;
            fullRgbSq += (dr * dr + dg * dg + db * db) / 3.0;

            boolean f1 = isForeground(r1, g1, b1);
            boolean f2 = isForeground(r2, g2, b2);
            if (f1 || f2) {
                fgUnion++;
                fgPercSq += psq;
                if (f1 && f2) {
                    fgInter++;
                    if (silPixels != null) {
                        silPixels[i] = pack(46, 204, 113);
                    }
                } else if (f1) {
                    fgRefOnly++;
                    if (silPixels != null) {
                        silPixels[i] = pack(231, 76, 60);
                    }
                } else {
                    fgRenOnly++;
                    if (silPixels != null) {
                        silPixels[i] = pack(52, 152, 219);
                    }
                }
            } else if (silPixels != null) {
                silPixels[i] = pack(240, 240, 240);
            }

            if (diffPixels != null) {
                diffPixels[i] = heatColor(Math.sqrt(psq));
            }
        }

        double fullRmse = Math.sqrt(fullPercSq / n);
        double rgbRmse = Math.sqrt(fullRgbSq / n);
        double psnr = rgbRmse == 0.0 ? 99.99 : 20.0 * Math.log10(255.0 / rgbRmse);
        double fgRmse = fgUnion > 0 ? Math.sqrt(fgPercSq / fgUnion) : 0.0;

        double silhouetteIoU = fgUnion > 0 ? (100.0 * fgInter / fgUnion) : 100.0;
        double fgColorScore = 100.0 * Math.max(0.0, 1.0 - fgRmse / 128.0);
        double fullColorScore = 100.0 * Math.max(0.0, 1.0 - fullRmse / 128.0);
        double ssimScore = 100.0 * computeSsim(refLuma, renLuma, w, h);

        double composite =
                0.30 * silhouetteIoU
                        + 0.35 * fgColorScore
                        + 0.20 * ssimScore
                        + 0.15 * fullColorScore;

        BufferedImage diffStrip = null;
        if (buildDiffStrip) {
            BufferedImage diffImg = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
            diffImg.setRGB(0, 0, w, h, diffPixels, 0, w);
            BufferedImage silImg = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
            silImg.setRGB(0, 0, w, h, silPixels, 0, w);
            diffStrip = buildFourPanelStrip(ref.image(), rendered, diffImg, silImg, composite);
        }

        return new ScoreResult(
                w,
                h,
                round2(composite),
                round2(silhouetteIoU),
                round2(fgColorScore),
                round2(ssimScore),
                round2(fullColorScore),
                round2(fgRmse),
                round2(fullRmse),
                round2(psnr),
                fgInter,
                fgRefOnly,
                fgRenOnly,
                diffStrip);
    }

    private static CachedRef loadReference(Path refPath, int width, int height) throws IOException {
        Path real = refPath.toRealPath();
        long mtime = Files.getLastModifiedTime(real).toMillis();
        RefKey key = new RefKey(real, mtime, width, height);
        CachedRef cached = REF_CACHE.get(key);
        if (cached != null) {
            return cached;
        }
        BufferedImage raw = ImageIO.read(real.toFile());
        if (raw == null) {
            throw new IOException("Unsupported reference image : " + refPath);
        }
        BufferedImage resized = resizeLanczos3(raw, width, height);
        int[] rgb = resized.getRGB(0, 0, width, height, null, 0, width);
        CachedRef result = new CachedRef(resized, rgb);
        REF_CACHE.put(key, result);
        return result;
    }

    /** Separable 2-pass Lanczos-3 resampler matching Pillow's {@code Image.Resampling.LANCZOS}. */
    private static BufferedImage resizeLanczos3(BufferedImage src, int dstW, int dstH) {
        int srcW = src.getWidth();
        int srcH = src.getHeight();
        int[] srcRgb = src.getRGB(0, 0, srcW, srcH, null, 0, srcW);
        if (srcW == dstW && srcH == dstH) {
            BufferedImage copy = new BufferedImage(dstW, dstH, BufferedImage.TYPE_INT_RGB);
            copy.setRGB(0, 0, dstW, dstH, srcRgb, 0, dstW);
            return copy;
        }

        // Pass 1: horizontal (srcW x srcH -> dstW x srcH)
        double[] tmpR = new double[dstW * srcH];
        double[] tmpG = new double[dstW * srcH];
        double[] tmpB = new double[dstW * srcH];
        double scaleX = (double) srcW / dstW;
        double supportX = Math.max(1.0, scaleX) * 3.0;
        double filterScaleX = Math.max(1.0, scaleX);

        for (int dx = 0; dx < dstW; dx++) {
            double center = (dx + 0.5) * scaleX - 0.5;
            int left = Math.max(0, (int) Math.floor(center - supportX));
            int right = Math.min(srcW - 1, (int) Math.ceil(center + supportX));
            int len = right - left + 1;
            double[] weights = new double[len];
            double wsum = 0.0;
            for (int i = 0; i < len; i++) {
                double w = lanczos3((left + i - center) / filterScaleX);
                weights[i] = w;
                wsum += w;
            }
            for (int y = 0; y < srcH; y++) {
                double r = 0.0;
                double g = 0.0;
                double b = 0.0;
                int rowOff = y * srcW + left;
                for (int i = 0; i < len; i++) {
                    int px = srcRgb[rowOff + i];
                    double w = weights[i];
                    r += ((px >> 16) & 0xFF) * w;
                    g += ((px >> 8) & 0xFF) * w;
                    b += (px & 0xFF) * w;
                }
                int outIdx = y * dstW + dx;
                tmpR[outIdx] = r / wsum;
                tmpG[outIdx] = g / wsum;
                tmpB[outIdx] = b / wsum;
            }
        }

        // Pass 2: vertical (dstW x srcH -> dstW x dstH)
        int[] dstRgb = new int[dstW * dstH];
        double scaleY = (double) srcH / dstH;
        double supportY = Math.max(1.0, scaleY) * 3.0;
        double filterScaleY = Math.max(1.0, scaleY);

        for (int dy = 0; dy < dstH; dy++) {
            double center = (dy + 0.5) * scaleY - 0.5;
            int top = Math.max(0, (int) Math.floor(center - supportY));
            int bottom = Math.min(srcH - 1, (int) Math.ceil(center + supportY));
            int len = bottom - top + 1;
            double[] weights = new double[len];
            double wsum = 0.0;
            for (int i = 0; i < len; i++) {
                double w = lanczos3((top + i - center) / filterScaleY);
                weights[i] = w;
                wsum += w;
            }
            for (int x = 0; x < dstW; x++) {
                double r = 0.0;
                double g = 0.0;
                double b = 0.0;
                for (int i = 0; i < len; i++) {
                    int idx = (top + i) * dstW + x;
                    double w = weights[i];
                    r += tmpR[idx] * w;
                    g += tmpG[idx] * w;
                    b += tmpB[idx] * w;
                }
                int ir = clampByte((int) Math.round(r / wsum));
                int ig = clampByte((int) Math.round(g / wsum));
                int ib = clampByte((int) Math.round(b / wsum));
                dstRgb[dy * dstW + x] = pack(ir, ig, ib);
            }
        }

        BufferedImage out = new BufferedImage(dstW, dstH, BufferedImage.TYPE_INT_RGB);
        out.setRGB(0, 0, dstW, dstH, dstRgb, 0, dstW);
        return out;
    }

    private static double lanczos3(double x) {
        double ax = Math.abs(x);
        if (ax < 1e-12) {
            return 1.0;
        }
        if (ax >= 3.0) {
            return 0.0;
        }
        double pix = Math.PI * ax;
        return (Math.sin(pix) / pix) * (Math.sin(pix / 3.0) / (pix / 3.0));
    }

    private static int clampByte(int v) {
        return Math.max(0, Math.min(255, v));
    }

    private static BufferedImage buildFourPanelStrip(
            BufferedImage refImg,
            BufferedImage renImg,
            BufferedImage diffImg,
            BufferedImage silImg,
            double composite) {
        int w = refImg.getWidth();
        int h = refImg.getHeight();
        int scale = w <= 200 ? 2 : 1;
        int pw = w * scale;
        int ph = h * scale;
        int header = 24;

        BufferedImage strip = new BufferedImage(pw * 4, ph + header, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = strip.createGraphics();
        try {
            g.setRenderingHint(
                    RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setRenderingHint(
                    RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
            g.setColor(new Color(30, 30, 34));
            g.fillRect(0, 0, strip.getWidth(), strip.getHeight());
            g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 12));

            String[] titles = {
                "Reference (" + w + "x" + h + ")",
                String.format(Locale.ROOT, "Rendered (%.2f/100)", composite),
                "Perceptual Error (2.5x)",
                "Silhouette (G=TP R=FN B=FP)"
            };
            BufferedImage[] panels = {refImg, renImg, diffImg, silImg};
            for (int i = 0; i < 4; i++) {
                int x = i * pw;
                g.drawImage(panels[i], x, header, pw, ph, null);
                g.setColor(new Color(235, 235, 240));
                g.drawString(titles[i], x + 6, 16);
            }
        } finally {
            g.dispose();
        }
        return strip;
    }

    private static boolean isForeground(int r, int g, int b) {
        return (255 - r) + (255 - g) + (255 - b) > FG_WHITE_THRESHOLD;
    }

    private static double perceptualSqDist(int r1, int g1, int b1, int r2, int g2, int b2) {
        double rmean = 0.5 * (r1 + r2);
        double dr = r1 - r2;
        double dg = g1 - g2;
        double db = b1 - b2;
        return ((2.0 + rmean / 256.0) * dr * dr
                        + 4.0 * dg * dg
                        + (2.0 + (255.0 - rmean) / 256.0) * db * db)
                / 9.0;
    }

    private static double computeSsim(double[] refLuma, double[] renLuma, int w, int h) {
        double c1 = (0.01 * 255.0) * (0.01 * 255.0);
        double c2 = (0.03 * 255.0) * (0.03 * 255.0);
        int win = 8;
        int stride = 4;
        if (w < win || h < win) {
            return 1.0;
        }
        double total = 0.0;
        int count = 0;
        double n = win * win;
        for (int y0 = 0; y0 <= h - win; y0 += stride) {
            for (int x0 = 0; x0 <= w - win; x0 += stride) {
                double sx = 0.0;
                double sy = 0.0;
                double sxx = 0.0;
                double syy = 0.0;
                double sxy = 0.0;
                for (int dy = 0; dy < win; dy++) {
                    int row = (y0 + dy) * w + x0;
                    for (int dx = 0; dx < win; dx++) {
                        double vx = refLuma[row + dx];
                        double vy = renLuma[row + dx];
                        sx += vx;
                        sy += vy;
                        sxx += vx * vx;
                        syy += vy * vy;
                        sxy += vx * vy;
                    }
                }
                double mx = sx / n;
                double my = sy / n;
                double vx = Math.max(0.0, sxx / n - mx * mx);
                double vy = Math.max(0.0, syy / n - my * my);
                double cov = sxy / n - mx * my;
                double num = (2.0 * mx * my + c1) * (2.0 * cov + c2);
                double den = (mx * mx + my * my + c1) * (vx + vy + c2);
                total += num / den;
                count++;
            }
        }
        return count > 0 ? Math.max(0.0, Math.min(1.0, total / count)) : 1.0;
    }

    private static int heatColor(double err) {
        double t = Math.max(0.0, Math.min(1.0, (err * 2.5) / 255.0));
        if (t < 0.25) {
            double u = t / 0.25;
            return pack(0, 0, (int) (255 * u));
        }
        if (t < 0.50) {
            double u = (t - 0.25) / 0.25;
            return pack(0, (int) (255 * u), 255);
        }
        if (t < 0.75) {
            double u = (t - 0.50) / 0.25;
            return pack((int) (255 * u), 255, (int) (255 * (1.0 - u)));
        }
        double u = (t - 0.75) / 0.25;
        return pack(255, (int) (255 * (1.0 - u)), 0);
    }

    private static int pack(int r, int g, int b) {
        return ((r & 0xFF) << 16) | ((g & 0xFF) << 8) | (b & 0xFF);
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
