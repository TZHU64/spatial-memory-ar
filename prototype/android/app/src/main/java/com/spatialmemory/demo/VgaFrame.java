package com.spatialmemory.demo;

import java.nio.ByteBuffer;

/** Pure plane conversion and quality math, independent of Android camera ownership. */
public final class VgaFrame {
    public final int width, height;
    public final int[] argb;
    public final float[] grid;
    public final double sharpness;
    public VgaFrame(ByteBuffer plane, int rowStride, int pixelStride, int left, int top, int width, int height) {
        this.width=width;this.height=height;
        if(width<32 || height<24 || pixelStride<1 || rowStride<1) throw new IllegalArgumentException("Invalid VGA plane");
        int start=plane.position(); byte[] y=new byte[width*height];argb=new int[y.length];
        for(int r=0;r<height;r++) for(int c=0;c<width;c++) {
            int value=plane.get(start+(top+r)*rowStride+(left+c)*pixelStride)&255;
            y[r*width+c]=(byte)value;argb[r*width+c]=0xff000000|value<<16|value<<8|value;
        }
        grid=new float[768];int sx=Math.max(1,width/160),sy=Math.max(1,height/120);
        for(int r=0;r<24;r++) for(int c=0;c<32;c++) {
            int x=(c*width+width/2)/32,yy=(r*height+height/2)/24,sum=0;
            for(int dy=-2;dy<=2;dy++) for(int dx=-2;dx<=2;dx++)
                sum+=y[Math.max(0,Math.min(height-1,yy+dy*sy))*width+Math.max(0,Math.min(width-1,x+dx*sx))]&255;
            grid[r*32+c]=sum/(25f*255);
        }
        // Area average into 320x240, then a 4-neighbor Laplacian with reflected borders.
        float[] small=new float[320*240];
        for(int r=0;r<240;r++) for(int c=0;c<320;c++) {
            int y0=r*height/240,y1=Math.max(y0+1,(r+1)*height/240);
            int x0=c*width/320,x1=Math.max(x0+1,(c+1)*width/320),sum=0;
            for(int yy=y0;yy<y1;yy++) for(int xx=x0;xx<x1;xx++) sum+=y[yy*width+xx]&255;
            small[r*320+c]=(float)sum/((y1-y0)*(x1-x0));
        }
        double sum=0,squares=0;
        for(int r=0;r<240;r++) for(int c=0;c<320;c++) {
            int up=r==0?1:r-1,down=r==239?238:r+1,leftX=c==0?1:c-1,right=c==319?318:c+1;
            double v=small[up*320+c]+small[down*320+c]+small[r*320+leftX]+small[r*320+right]-4*small[r*320+c];
            sum+=v;squares+=v*v;
        }
        sharpness=squares/small.length-Math.pow(sum/small.length,2);
    }
}
