package com.spatialmemory.demo;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/** Stateful S0/S1 policy. Features and banks never leave the glasses. Times are monotonic ms. */
public final class LayeredSceneGate {
    public static final int BANK_LIMIT = 64, CAPTURE_LIMIT = 12;
    public static final float GLOBAL = .45f, REGION = .50f, REGION_GLOBAL = .12f;
    private final List<float[][]> bank = new ArrayList<>();
    private float[] lastGrid;
    private float[][] latest, pending, inFlight;
    private long pendingSince;
    private int pendingCount, captures, checks, qualityRejects;
    private double global, region;
    private String state = "Looking for a new view";

    /** Called on the model thread before any expensive embedding. */
    public synchronized boolean shouldEmbed(float[] grid, double sharpness) {
        if (grid.length != 768) throw new IllegalArgumentException("Expected 32x24 luminance grid");
        double mean = 0;
        for (float v : grid) { if (!Float.isFinite(v)) throw new IllegalArgumentException("Invalid luminance"); mean += v; }
        mean /= grid.length;
        if (mean < .03 || mean > .98 || !Double.isFinite(sharpness) || sharpness < 22) {
            qualityRejects++; clearPending(); state = "Waiting for a clearer view"; return false;
        }
        if (inFlight != null) return false;
        if (captures >= CAPTURE_LIMIT) { state = "Automatic photo limit reached; Stop and Resume to continue"; return false; }
        boolean wake = bank.isEmpty() || lastGrid == null || pending != null;
        if (!wake) {
            double[] difference = difference(lastGrid, grid);
            wake = difference[0] >= .025 || difference[1] >= .065;
        }
        if (wake) lastGrid = grid.clone();
        return wake;
    }

    /** Main-thread delivery of one completed inference; no second capture while one is outstanding. */
    public synchronized boolean sample(float[][] features, long now, boolean available) {
        validate(features); checks++; latest = copy(features);
        if (inFlight != null || captures >= CAPTURE_LIMIT) return false;
        double[] distances = novelty(features, bank); global = distances[0]; region = distances[1];
        boolean changed = global >= GLOBAL || (region >= REGION && global >= REGION_GLOBAL);
        if (!changed) { clearPending(); state = "Familiar view"; return false; }
        if (pending == null || distance(features[0], pending[0]) > .16) {
            pending = copy(features); pendingSince = now; pendingCount = 1;
        } else pendingCount++;
        state = "Checking a new view";
        if (pendingCount < 4 || now - pendingSince < 600) return false;
        if (!available) { state = "Waiting for photo queue"; return false; }
        inFlight = copy(features); clearPending(); state = "Saving a new view";
        return true;
    }

    public synchronized void beginManualCapture() {
        // A manual capture can bypass S1, but only a real previous feature set can be committed.
        inFlight = latest == null ? null : copy(latest); clearPending();
    }
    public synchronized void complete(boolean success) {
        if (success && inFlight != null) {
            bank.add(copy(inFlight)); if (bank.size() > BANK_LIMIT) bank.remove(0); captures++;
        }
        inFlight = null; clearPending();
    }
    public synchronized void streamRestarted() { lastGrid = null; latest = null; clearPending(); }
    private void clearPending() { pending = null; pendingCount = 0; }
    public synchronized String status() { return state; }
    public synchronized String diagnostics(long now) {
        return "s1="+checks+" qualityRejected="+qualityRejects+" accepted="+captures+" bank="+bank.size()
            +" G="+global+" R="+region+" pending="+pendingCount;
    }

    public static double distance(float[] a, float[] b) {
        if (a.length != b.length) throw new IllegalArgumentException("Feature shape mismatch");
        double dot = 0; for (int i=0;i<a.length;i++) dot += a[i]*b[i];
        return Math.max(0, Math.min(2, 1-dot));
    }
    public static double[] novelty(float[][] features, List<float[][]> bank) {
        if (bank.isEmpty()) return new double[]{1,1};
        Double[] distances = new Double[bank.size()]; Integer[] order = new Integer[bank.size()];
        for (int i=0;i<bank.size();i++) { distances[i]=distance(features[0],bank.get(i)[0]); order[i]=i; }
        Arrays.sort(order, Comparator.comparingDouble(i -> distances[i]));
        double local = 0;
        for (int crop=1;crop<5;crop++) {
            double closest = 2;
            for (int n=0;n<Math.min(3,order.length);n++)
                for (int ref=1;ref<5;ref++) closest=Math.min(closest,distance(features[crop],bank.get(order[n])[ref]));
            local=Math.max(local,closest);
        }
        return new double[]{distances[order[0]],local};
    }
    private static void validate(float[][] f) {
        if (f.length != 5 || f[0].length == 0) throw new IllegalArgumentException("Expected five feature vectors");
        for (float[] row : f) {
            if(row.length != f[0].length) throw new IllegalArgumentException("Feature shape mismatch");
            double norm=0;
            for(float v:row) { if(!Float.isFinite(v)) throw new IllegalArgumentException("Invalid feature"); norm+=v*v; }
            if(Math.abs(norm-1)>.01) throw new IllegalArgumentException("Features must be normalized");
        }
    }
    private static float[][] copy(float[][] source) {
        float[][] result=new float[source.length][];
        for(int i=0;i<source.length;i++) result[i]=source[i].clone();
        return result;
    }
    private static double[] difference(float[] old, float[] current) {
        double am=0,bm=0;
        for(int i=0;i<768;i++) { am+=old[i];bm+=current[i]; }
        am/=768;bm/=768;
        double ab=0,bb=0;
        for(int i=0;i<768;i++) { double a=old[i]-am,b=current[i]-bm;ab+=a*b;bb+=b*b; }
        double gain=Math.max(.6,Math.min(1.6,ab/Math.max(bb,1e-6))),whole=0,patch=0;
        double[] residual=new double[768];
        for(int i=0;i<768;i++) { residual[i]=Math.abs(old[i]-am-gain*(current[i]-bm));whole+=residual[i]; }
        for(int y=0;y<=18;y+=3) for(int x=0;x<=24;x+=4) {
            double sum=0;
            for(int dy=0;dy<6;dy++) for(int dx=0;dx<8;dx++) sum+=residual[(y+dy)*32+x+dx];
            patch=Math.max(patch,sum/48);
        }
        return new double[]{whole/768,patch};
    }
}
