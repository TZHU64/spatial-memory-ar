package com.spatialmemory.recorder;

import java.nio.ByteBuffer;

/** Copies one visible plane without assuming packed YUV or changing buffer positions. */
public final class PlaneCopy {
    private PlaneCopy() {}

    public static void copy(ByteBuffer source, int sourceRow, int sourcePixel, int left, int top,
                            ByteBuffer target, int targetRow, int targetPixel, int targetLeft, int targetTop,
                            int width, int height) {
        ByteBuffer src = source.duplicate();
        ByteBuffer dst = target.duplicate();
        int srcBase = src.position() + top * sourceRow + left * sourcePixel;
        int dstBase = dst.position() + targetTop * targetRow + targetLeft * targetPixel;
        check(src, srcBase, sourceRow, sourcePixel, width, height);
        check(dst, dstBase, targetRow, targetPixel, width, height);
        byte[] row = new byte[width];
        for (int y = 0; y < height; y++) {
            if (sourcePixel == 1) {
                src.position(srcBase + y * sourceRow);
                src.get(row);
            } else {
                for (int x = 0; x < width; x++) row[x] = src.get(srcBase + y * sourceRow + x * sourcePixel);
            }
            if (targetPixel == 1) {
                dst.position(dstBase + y * targetRow);
                dst.put(row);
            } else {
                for (int x = 0; x < width; x++) dst.put(dstBase + y * targetRow + x * targetPixel, row[x]);
            }
        }
    }

    public static void neutral(ByteBuffer target, int row, int pixel, int left, int top, int width, int height) {
        ByteBuffer dst = target.duplicate();
        int base = dst.position() + top * row + left * pixel;
        check(dst, base, row, pixel, width, height);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) dst.put(base + y * row + x * pixel, (byte) 128);
        }
    }

    private static void check(ByteBuffer buffer, int base, int row, int pixel, int width, int height) {
        long last = base + (long) (height - 1) * row + (long) (width - 1) * pixel;
        if (width <= 0 || height <= 0 || row <= 0 || pixel <= 0 || base < 0 || last >= buffer.limit()) {
            throw new IllegalArgumentException("Plane does not contain the requested crop");
        }
    }
}
