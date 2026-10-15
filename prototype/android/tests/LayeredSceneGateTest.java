import com.spatialmemory.demo.LayeredSceneGate;
import com.spatialmemory.demo.VgaFrame;
import java.nio.ByteBuffer;
import java.util.ArrayList;

public final class LayeredSceneGateTest {
    static void check(boolean value,String why) { if(!value) throw new AssertionError(why); }
    static float[][] feature(int axis) {
        float[][] f=new float[5][8];for(int i=0;i<5;i++) f[i][axis]=1;return f;
    }
    static boolean feed(LayeredSceneGate g,float[][] f,long start,boolean available) {
        boolean proposed=false;
        for(int i=0;i<4;i++) proposed |= g.sample(f,start+i*200,available);
        return proposed;
    }
    public static void main(String[] args) {
        LayeredSceneGate g=new LayeredSceneGate();float[][] a=feature(0),b=feature(1);
        check(!g.sample(a,0,true),"No instant initial capture");
        check(!g.sample(a,200,true),"Need elapsed persistence");
        check(!g.sample(a,400,true),"Need four samples");
        check(g.sample(a,600,true),"Persistent initial view captures");
        check(!g.sample(b,800,true),"Only one acquisition outstanding");
        g.complete(true);g.streamRestarted();
        check(!feed(g,a,1000,true),"Familiar view after camera restart stays quiet");
        check(!feed(g,b,2000,false),"Queue backpressure prevents acquisition");
        check(g.sample(b,2800,true),"Persistent candidate captures when queue frees");
        g.complete(false);g.streamRestarted();
        check(feed(g,b,3000,true),"Failed acquisition must not enter evidence bank");g.complete(true);
        check(!feed(g,a,4000,true),"Returning to familiar evidence stays quiet");
        check(!feed(g,b,5000,true),"No periodic capture of remembered view");
        float[][] local=feature(0);local[4]=new float[]{0,0,1,0,0,0,0,0};
        check(!feed(g,local,6000,true),"Global minimum protects crop-only fluctuation");
        local[0]=new float[]{.8f,0,.6f,0,0,0,0,0};
        check(feed(g,local,7000,true),"Persistent region novelty with global support captures");g.complete(true);
        float[] grid=new float[768];java.util.Arrays.fill(grid,.5f);
        LayeredSceneGate quality=new LayeredSceneGate();
        check(!quality.shouldEmbed(grid,0),"Blur rejected before model");
        check(quality.shouldEmbed(grid,100),"Empty bank wakes on usable view");
        check(feed(quality,a,0,true),"Establish quality bank");quality.complete(true);
        check(!quality.shouldEmbed(grid,100),"Static pixels bypass S1");
        for(int i=0;i<grid.length;i++) grid[i]=grid[i]*1.2f+.1f;
        check(!quality.shouldEmbed(grid,100),"Uniform exposure does not wake S1");
        LayeredSceneGate anchor=new LayeredSceneGate();
        for(int i=0;i<20;i++) check(!anchor.sample(i%2==0?a:b,i*200,true),"Unstable candidates never persist");
        LayeredSceneGate budget=new LayeredSceneGate();
        for(int n=0;n<12;n++) {
            float[][] unique=new float[5][16];for(int i=0;i<5;i++) unique[i][n]=1;
            check(feed(budget,unique,n*1000,true),"Budget allows first twelve distinct views");budget.complete(true);
        }
        float[][] another=new float[5][16];for(int i=0;i<5;i++) another[i][15]=1;
        check(!feed(budget,another,13000,true),"Budget stops runaway automatic acquisition");
        ByteBuffer plane=ByteBuffer.allocate(700*500*2+7);plane.position(7);
        for(int y=0;y<500;y++) for(int x=0;x<700;x++) plane.put(7+y*1400+x*2,(byte)((x+y)%256));
        VgaFrame frame=new VgaFrame(plane,1400,2,11,13,640,480);
        check((frame.argb[0]&255)==24,"Plane conversion respects crop, offset, row and pixel stride");
        check(frame.grid.length==768 && Double.isFinite(frame.sharpness),"Finite grid and sharpness");
        System.out.println("PASS: persistence, familiar views, restart, backpressure, failure retry, region changes, exposure, quality, budget, strided crop");
    }
}
