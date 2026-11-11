import com.unixroot.win10.clr.*;
import com.unixroot.win10.core.*;
import javax.imageio.ImageIO;
import java.nio.file.*;
public class ClrGui {
  static GTest.S top;
  public static void main(String[] a) throws Exception {
    java.io.File exe = new java.io.File(a[0]).getAbsoluteFile();
    final GuiHost.Input[] in = new GuiHost.Input[1]; final int[] win = new int[1];
    GuiHost host = new GuiHost() {
      public HostWindow createWindow(GuiHost.Input g, int hwnd, String title, int cw, int ch) { in[0]=g; win[0]=hwnd; top=new GTest.S(cw,ch); System.out.println("window '"+title+"' "+cw+"x"+ch);
        return new HostWindow(){ public Surface surface(){return top;} public void setTitle(String t){} public void setVisible(boolean v){} public void close(){ System.out.println("host close"); } }; }
      public Surface createSurface(int w,int h){ return new GTest.S(w,h); }
      public void messageBox(String t,String x){ System.out.println("[MsgBox] "+t+": "+x); }
    };
    Vm vm = new Vm(exe.getParentFile(), new Vm.Console(){ public void write(String s){System.out.print(s);} public int read(){return -1;} public void messageBox(String t,String x){ System.out.println("[MsgBox] "+t+": "+x);} });
    vm.fsRoot = new java.io.File(exe.getParentFile(), "C_root"); vm.fsRoot.mkdirs();
    vm.bcl.lenient = true; WinForms.install(vm, host);
    vm.main = vm.load(Files.readAllBytes(exe.toPath()), "wf");
    new Thread(() -> { try { Thread.sleep(500);
      for (int i=0;i<3;i++){ in[0].postMouse(win[0],0x201,30,60); in[0].postMouse(win[0],0x202,30,60); }
      Thread.sleep(300); ImageIO.write(top.im,"png",new java.io.File(a[1]));
      in[0].windowClosed(win[0]); } catch(Exception e){ e.printStackTrace(); } }).start();
    System.out.println("exit=" + vm.runMain(new String[0]));
  }
}
