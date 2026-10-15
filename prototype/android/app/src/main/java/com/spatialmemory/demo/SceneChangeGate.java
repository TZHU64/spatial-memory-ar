package com.spatialmemory.demo;

import java.util.Locale;

/** Image-content trigger; elapsed time only rejects unsettled motion, never schedules photos. */
public final class SceneChangeGate {
    private static final int WIDTH = 32, HEIGHT = 24;
    private static final int RECENT_VIEWS = 6;
    private static final float WHOLE_CHANGE = 0.10f, PATCH_CHANGE = 0.22f;
    private static final long STEADY_MS = 2500;
    private static final long MOTION_WINDOW_MS = 5000, POST_WALK_DWELL_MS = 8000;
    private static final int WALK_MOTION_SAMPLES = 6;
    private String status = "Waiting for VGA";
    public String status() { return status; }
    private float[] previous;
    private float[] stableAnchor;
    private final float[][] remembered = new float[RECENT_VIEWS][];
    private int rememberedCount, nextRemembered;
    private float[] pending;
    private long stableSince = -1;
    private long changedSince = -1;
    private long started = -1;
    private long motionWindowStarted = -1, suppressUntil = -1;
    private int motionSamples;
    private float lastWhole, lastPatch, lastFrame, lastAnchor;
    private int lastShiftX, lastShiftY;

    public String diagnostics(long now) {
        return String.format(Locale.US,"whole=%.3f patch=%.3f frame=%.3f anchor=%.3f shift=%d,%d steadyMs=%d walkWaitMs=%d",
            lastWhole,lastPatch,lastFrame,lastAnchor,lastShiftX,lastShiftY,
            stableSince < 0 ? 0 : now-stableSince,Math.max(0,suppressUntil-now));
    }

    public boolean sample(float[] luminance, long now, boolean available) {
        if (luminance == null || luminance.length != WIDTH * HEIGHT) throw new IllegalArgumentException("Expected 32x24 VGA grid");
        if (started < 0) started = now;
        float mean = 0;
        for (float value : luminance) mean += value;
        mean /= luminance.length;
        float[] centered = new float[luminance.length];
        for (int i=0;i<luminance.length;i++) centered[i]=luminance[i]-mean;

        boolean exposed = mean >= 0.025f && mean <= 0.98f;
        // Compare to the first frame of the steady window as well as the previous frame.
        // This prevents slow head drift from accumulating into a false stable change.
        Diff adjacent = previous == null ? null : compare(previous,centered,false);
        Diff anchor = stableAnchor == null ? null : compare(stableAnchor,centered,false);
        lastFrame = adjacent == null ? 0 : adjacent.mean;
        lastAnchor = anchor == null ? 0 : anchor.mean;
        boolean steady = exposed && adjacent != null && quiet(adjacent);
        if (steady && anchor != null) steady = quiet(anchor);
        if (exposed && adjacent != null && !steady) {
            if (motionWindowStarted < 0 || now-motionWindowStarted > MOTION_WINDOW_MS) {
                motionWindowStarted=now;
                motionSamples=0;
            }
            motionSamples++;
            if (motionSamples >= WALK_MOTION_SAMPLES || now < suppressUntil)
                suppressUntil=now+POST_WALK_DWELL_MS;
        }
        if (!steady) {
            stableAnchor = centered;
            stableSince = -1;
        } else if (stableSince < 0) stableSince = now;
        previous = centered;

        // Compare against several recent captures. A return to a previously photographed
        // view is not new evidence. Matching tolerates a one-cell shift and exposure gain.
        boolean changed = rememberedCount == 0;
        float closest = Float.POSITIVE_INFINITY;
        if (rememberedCount > 0) changed = true;
        for (int i=0;i<rememberedCount;i++) {
            Diff baseline = compare(remembered[i],centered,true);
            float score = Math.max(baseline.mean/WHOLE_CHANGE,baseline.patch/PATCH_CHANGE);
            if (score < closest) {
                closest=score; lastWhole=baseline.mean; lastPatch=baseline.patch;
                lastShiftX=baseline.shiftX; lastShiftY=baseline.shiftY;
            }
            if (baseline.mean <= WHOLE_CHANGE && baseline.patch <= PATCH_CHANGE) changed=false;
        }
        if (!changed || stableSince < 0) changedSince = -1;
        else if (changedSince < 0) changedSince = now;
        boolean ready = stableSince >= 0 && changedSince >= 0
            && now-stableSince >= STEADY_MS && now-changedSince >= STEADY_MS
            && now >= suppressUntil;
        status = !available ? "Capture busy or queue full" : pending != null ? "Taking photo"
            : !exposed ? "View too dark or bright"
            : now < suppressUntil ? "Walking; waiting for deliberate look"
            : !changed ? "View unchanged" : !ready
            ? "Change detected; waiting for steady view" : "Changed view ready";
        if (available && pending == null && changed && ready) {
            pending = centered.clone();
            return true;
        }
        return false;
    }

    private static boolean quiet(Diff d) { return d.mean < 0.035f && d.patch < 0.07f; }

    private static final class Diff {
        final float mean, patch;
        final int shiftX, shiftY;
        Diff(float mean, float patch, int shiftX, int shiftY) {
            this.mean=mean; this.patch=patch; this.shiftX=shiftX; this.shiftY=shiftY;
        }
    }

    private static Diff compare(float[] a, float[] b, boolean align) {
        Diff best = null;
        float bestScore = Float.POSITIVE_INFINITY;
        int radius = align ? 1 : 0;
        for (int dy=-radius;dy<=radius;dy++) for (int dx=-radius;dx<=radius;dx++) {
            int x0=Math.max(0,-dx), x1=Math.min(WIDTH,WIDTH-dx);
            int y0=Math.max(0,-dy), y1=Math.min(HEIGHT,HEIGHT-dy);
            float dot=0, power=0;
            for (int y=y0;y<y1;y++) for (int x=x0;x<x1;x++) {
                float old=a[y*WIDTH+x], current=b[(y+dy)*WIDTH+x+dx];
                dot+=old*current; power+=current*current;
            }
            float gain=power < 0.001f ? 1f : Math.max(0.6f,Math.min(1.6f,dot/power));
            float total=0, patchMax=0;
            int count=0;
            for (int y=y0;y<y1;y++) for (int x=x0;x<x1;x++) {
                total+=Math.abs(a[y*WIDTH+x]-gain*b[(y+dy)*WIDTH+x+dx]);
                count++;
            }
            for (int top=0;top<=HEIGHT-6;top+=3) for (int left=0;left<=WIDTH-8;left+=4) {
                float patchSum=0;
                int patchCount=0;
                for (int y=Math.max(top,y0);y<Math.min(top+6,y1);y++)
                    for (int x=Math.max(left,x0);x<Math.min(left+8,x1);x++) {
                        float delta=Math.abs(a[y*WIDTH+x]-gain*b[(y+dy)*WIDTH+x+dx]);
                        patchSum+=delta; patchCount++;
                    }
                if (patchCount >= 24) patchMax=Math.max(patchMax,patchSum/patchCount);
            }
            Diff value=new Diff(total/count,patchMax,dx,dy);
            float score=value.mean+0.003f*(Math.abs(dx)+Math.abs(dy));
            if (score < bestScore) { bestScore=score; best=value; }
        }
        return best;
    }

    public void beginManualCapture() { if (pending == null && previous != null) pending=previous.clone(); }
    public void complete(boolean accepted) {
        if (accepted && pending != null) {
            remembered[nextRemembered] = pending;
            nextRemembered = (nextRemembered+1)%RECENT_VIEWS;
            rememberedCount = Math.min(rememberedCount+1,RECENT_VIEWS);
        }
        pending = null;
        changedSince = -1;
    }
    /** Reopening VGA must settle exposure again but retains the last captured scene. */
    public void streamRestarted() {
        previous=null; stableAnchor=null; stableSince=-1; changedSince=-1; started=-1;
        motionWindowStarted=-1; motionSamples=0;
    }
}
