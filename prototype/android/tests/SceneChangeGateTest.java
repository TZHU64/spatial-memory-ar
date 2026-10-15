import com.spatialmemory.demo.SceneChangeGate;

public final class SceneChangeGateTest {
    static void check(boolean value,String message) { if(!value) throw new AssertionError(message); }
    static float[] scene() {
        float[] values=new float[768];
        for(int y=0;y<24;y++) for(int x=0;x<32;x++)
            values[y*32+x]=(float)(.46+.12*Math.sin(x*.43)+.10*Math.cos(y*.37)+.06*((x/5+y/4)%2));
        return values;
    }
    static float[] exposure(float[] source,float gain,float offset) {
        float[] result=source.clone();
        for(int i=0;i<result.length;i++) result[i]=result[i]*gain+offset;
        return result;
    }
    static float[] shift(float[] source,int dx) {
        float[] result=new float[768];
        for(int y=0;y<24;y++) for(int x=0;x<32;x++)
            result[y*32+x]=source[y*32+Math.max(0,Math.min(31,x-dx))];
        return result;
    }
    static float[] localChange(float[] source,float amount) {
        float[] result=source.clone();
        for(int y=9;y<15;y++) for(int x=12;x<20;x++) result[y*32+x]=Math.min(1f,result[y*32+x]+amount);
        return result;
    }
    static int feed(SceneChangeGate gate,float[] view,long start,long end,boolean available) {
        int count=0;
        for(long t=start;t<=end;t+=150) if(gate.sample(view,t,available)) { count++;gate.complete(true); }
        return count;
    }
    public static void main(String[] args) {
        float[] base=scene();
        SceneChangeGate gate=new SceneChangeGate();
        check(feed(gate,base,0,3600,true)==1,"First steady view must trigger once");
        check(feed(gate,base,2550,60000,true)==0,"Unchanged scene must not trigger periodically");
        check(feed(gate,exposure(base,1.25f,-.05f),60150,63000,true)==0,
            "Exposure gain and offset must not trigger another RGB photo");
        gate.streamRestarted();
        check(feed(gate,shift(base,1),63150,66500,true)==0,
            "A one-cell camera shift after VGA reopening must not retrigger");
        check(feed(gate,localChange(base,.2f),66650,70000,true)==0,
            "A small patch fluctuation must not trigger another RGB photo");
        float[] changed=localChange(base,.45f);
        int localTriggers=feed(gate,changed,70150,73500,true);
        check(localTriggers==1,
            "A sustained, distinct local object change must still trigger");
        check(feed(gate,changed,73650,75500,true)==0,"Accepted change must not duplicate");

        SceneChangeGate moving=new SceneChangeGate();
        check(feed(moving,base,0,3600,true)==1,"Initial moving-test baseline");
        float[] other=exposure(base,-.7f,1f);
        for(long t=3750;t<=7200;t+=150)
            check(!moving.sample((t/150)%2==0?base:other,t,true),"Moving view must not capture");
        check(moving.status().contains("Walking"),"Sustained motion must enter walking suppression");
        check(feed(moving,other,7350,13950,true)==0,"Brief stops during a walk must not capture");
        check(feed(moving,other,14100,15600,false)==0,"Busy pipeline must not capture");
        check(moving.sample(other,15750,true),"Deliberate settled look should capture when available");
        check(!moving.sample(other,15900,true),"Pending capture must not duplicate");
        moving.complete(true);moving.streamRestarted();
        check(feed(moving,other,16050,18900,true)==0,"Reopening must retain new baseline");
        check(feed(moving,base,19050,22350,true)==0,"Returning to a recent view must not capture");
        check(feed(moving,new float[768],22500,25350,true)==0,"Black frames must not capture");
        System.out.println("PASS: steady capture, still suppression, exposure, shift, local change, walking dwell, backpressure, restart, dark frames");
    }
}
