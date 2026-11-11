package com.unixroot.win10.clr;
import com.unixroot.win10.core.*;
import javax.imageio.ImageIO;
import java.nio.file.*; import java.awt.*; import java.awt.image.*;
public class XamlGui {
  public static void main(String[] a) throws Exception {
    final GuiHost.Input[] in=new GuiHost.Input[1]; final S[] top=new S[1];
    GuiHost host=new GuiHost(){
      public HostWindow createWindow(GuiHost.Input g,int hwnd,String t,int cw,int ch){ top[0]=new S(cw,ch); System.out.println("window "+t+" "+cw+"x"+ch);
        return new HostWindow(){ public Surface surface(){return top[0];} public void setTitle(String s){} public void setVisible(boolean v){} public void close(){} }; }
      public Surface createSurface(int w,int h){ return new S(w,h); }
      public void messageBox(String t,String x){} };
    Vm vm=new Vm(new java.io.File("."),new Vm.Console(){public void write(String s){System.out.print(s);} public int read(){return -1;} public void messageBox(String t,String x){}});
    WinForms.install(vm,host);
    WinForms wf=null; for(Object e: vm.bcl.exts) if(e instanceof WinForms) wf=(WinForms)e;
    WinForms.Ctl w=wf.wpf.loadXaml(new String(Files.readAllBytes(Paths.get(a[0])),"UTF-8"),null);
    wf.showForm(w);
    wf.pump(10);
    ImageIO.write(top[0].im,"png",new java.io.File(a[1]));
  }
  static class S implements Surface {
    BufferedImage im; Graphics2D g;
    S(int w,int h){ im=new BufferedImage(w,h,BufferedImage.TYPE_INT_RGB); g=im.createGraphics(); g.setColor(Color.WHITE); g.fillRect(0,0,w,h);}
    public int width(){return im.getWidth();} public int height(){return im.getHeight();}
    public void fillRect(int x,int y,int w,int h,int c){g.setColor(new Color(c)); g.fillRect(x,y,w,h);}
    public void rect(int x,int y,int w,int h,int f,int p,int pw){ if(f>=0){g.setColor(new Color(f)); g.fillRect(x,y,w,h);} if(p>=0){g.setColor(new Color(p)); g.drawRect(x,y,w-1,h-1);} }
    public void roundRect(int x,int y,int w,int h,int rx,int ry,int f,int p,int pw){ if(f>=0){g.setColor(new Color(f)); g.fillRoundRect(x,y,w,h,rx,ry);} if(p>=0){g.setColor(new Color(p)); g.drawRoundRect(x,y,w-1,h-1,rx,ry);} }
    public void ellipse(int x,int y,int w,int h,int f,int p,int pw){ if(f>=0){g.setColor(new Color(f)); g.fillOval(x,y,w,h);} if(p>=0){g.setColor(new Color(p)); g.drawOval(x,y,w-1,h-1);} }
    public void line(int a,int b,int c,int d,int rgb,int w){g.setColor(new Color(rgb)); g.drawLine(a,b,c,d);}
    public void polygon(int[] xs,int[] ys,int n,boolean cl,int f,int p,int pw){ if(f>=0){g.setColor(new Color(f)); g.fillPolygon(xs,ys,n);} if(p>=0){g.setColor(new Color(p)); if(cl) g.drawPolygon(xs,ys,n); else g.drawPolyline(xs,ys,n);} }
    public void text(String s,int x,int y,int fg,int bg,int fh,boolean b,boolean i){ g.setFont(new Font("SansSerif",(b?Font.BOLD:0)|(i?Font.ITALIC:0),Math.max(8,fh*3/4))); FontMetrics fm=g.getFontMetrics(); if(bg>=0){g.setColor(new Color(bg)); g.fillRect(x,y,fm.stringWidth(s),fh);} g.setColor(new Color(fg)); g.drawString(s,x,y+fm.getAscent()); }
    public int textWidth(String s,int fh,boolean b){ g.setFont(new Font("SansSerif",b?Font.BOLD:0,Math.max(8,fh*3/4))); return g.getFontMetrics().stringWidth(s); }
    public void blit(Surface src,int sx,int sy,int sw,int sh,int dx,int dy,int dw,int dh){ g.drawImage(((S)src).im,dx,dy,dx+dw,dy+dh,sx,sy,sx+sw,sy+sh,null); }
    public void pixels(int[] rgb,int w,int h,int dx,int dy,int dw,int dh){ BufferedImage t=new BufferedImage(w,h,BufferedImage.TYPE_INT_RGB); t.setRGB(0,0,w,h,rgb,0,w); g.drawImage(t,dx,dy,dw,dh,null);}
    public int getPixel(int x,int y){ if(x<0||y<0||x>=width()||y>=height()) return 0; return im.getRGB(x,y)&0xFFFFFF; }
    public void setPixel(int x,int y,int c){ if(x>=0&&y>=0&&x<width()&&y<height()) im.setRGB(x,y,c); }
    public void flush(){}
  }
  static S top; static int topHwnd;

}
