import com.unixroot.win10.clr.*;
import java.nio.file.*;
public class Dump { public static void main(String[] a) throws Exception {
  Metadata m=new Metadata(Files.readAllBytes(Paths.get(a[0])));
  System.out.println("flags="+m.corFlags+" entry="+Integer.toHexString(m.entryPointToken));
  for(int t=0;t<0x2D;t++) if(m.rowCount[t]>0) System.out.print(Integer.toHexString(t)+":"+m.rowCount[t]+" ");
  System.out.println();
  for(int i=1;i<=Math.min(8,m.count(2));i++){int[] r=m.row(2,i); System.out.println(m.string(r[2])+"."+m.string(r[1])+" fields@"+r[4]+" methods@"+r[5]);}
  int[] mr=m.row(Metadata.METHOD,Math.min(5,m.count(6))); System.out.println("method "+m.string(mr[3])+" rva="+Integer.toHexString(mr[0])+" sig="+m.blob(mr[4]).length);
  for(int i=1;i<=m.count(0x23);i++) System.out.println("asmref "+m.string(m.row(0x23,i)[6]));
}}
