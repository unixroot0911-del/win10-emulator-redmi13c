import com.unixroot.win10.clr.*;
import java.nio.file.*;
public class ClrTest {
  public static void main(String[] a) throws Exception {
    java.io.File exe = new java.io.File(a[0]).getAbsoluteFile();
    Vm vm = new Vm(exe.getParentFile(), new Vm.Console(){
      public void write(String s){ System.out.print(s); System.out.flush(); }
      public int read(){ return -1; }
      public void messageBox(String t,String x){ System.out.println("[MsgBox] "+t+": "+x); }
    });
    vm.fsRoot = new java.io.File(exe.getParentFile(), "C_root"); vm.fsRoot.mkdirs();
    vm.main = vm.load(Files.readAllBytes(exe.toPath()), exe.getName().replaceAll("\\.[^.]*$", ""));
    long t0 = System.nanoTime();
    int code = vm.runMain(new String[0]);
    System.out.println("exit=" + code + " ms=" + (System.nanoTime()-t0)/1000000);
  }
}
