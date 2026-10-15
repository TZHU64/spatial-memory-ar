package com.spatialmemory.recorder;

import java.nio.ByteBuffer;
import java.util.Arrays;

public final class PlaneCopyTest {
    public static void main(String[] args) {
        // Cropped source with row padding -> padded output. Positions are offsets, not zero.
        ByteBuffer src = ByteBuffer.wrap(new byte[]{99, 10,11,12,13,88, 20,21,22,23,88, 30,31,32,33});
        src.position(1);
        ByteBuffer dst = ByteBuffer.allocate(15);
        dst.position(2);
        PlaneCopy.copy(src, 5, 1, 1, 1, dst, 5, 1, 0, 0, 3, 2);
        require(dst.get(2) == 21 && dst.get(4) == 23 && dst.get(7) == 31 && dst.get(9) == 33, "cropped rows");
        require(src.position() == 1 && dst.position() == 2, "buffer positions");

        // NV21/NV12 chroma views may overlap and have pixel stride 2.
        ByteBuffer interleaved = ByteBuffer.wrap(new byte[]{1,101,2,102,99,99, 3,103,4,104});
        ByteBuffer planar = ByteBuffer.allocate(4);
        PlaneCopy.copy(interleaved, 6, 2, 0, 0, planar, 2, 1, 0, 0, 2, 2);
        require(Arrays.equals(planar.array(), new byte[]{1,2,3,4}), "strided chroma with short last row");
        ByteBuffer uv = ByteBuffer.allocate(10);
        uv.position(1);
        PlaneCopy.copy(planar, 2, 1, 0, 0, uv, 6, 2, 0, 0, 2, 2);
        require(uv.get(1) == 1 && uv.get(3) == 2 && uv.get(7) == 3 && uv.get(9) == 4, "strided encoder input");
        PlaneCopy.neutral(uv, 6, 2, 0, 0, 2, 2);
        require((uv.get(1) & 255) == 128 && (uv.get(9) & 255) == 128 && uv.get(2) == 0, "mono neutral chroma");

        boolean rejected = false;
        try { PlaneCopy.copy(src, 5, 1, 0, 0, ByteBuffer.allocate(2), 3, 1, 0, 0, 3, 1); }
        catch (IllegalArgumentException expected) { rejected = true; }
        require(rejected, "invalid crop rejected");
        System.out.println("PlaneCopy: 6 image-layout checks passed");
    }

    private static void require(boolean condition, String label) {
        if (!condition) throw new AssertionError(label);
    }
}
