package diagnostics;
import cuckoo.CuckooHashMap;
import java.util.*;
public class Probe3 {
  static long sink;
  static int scr(int x){x*=0x9E3779B1;x^=x>>>15;x*=0x2C1B3C6D;x^=x>>>12;x*=0x297A2D39;x^=x>>>15;return x;}
  public static void main(String[] a){
    int b=Integer.parseInt(a[0]); int n=Integer.parseInt(a[1]); double ml=Double.parseDouble(a[2]);
    CuckooHashMap<Integer,Integer> m = CuckooHashMap.builder().bucketSize(b).maxLoad(ml).stash(4).expectedSize(n).build();
    Integer[] keys=new Integer[n]; for(int i=0;i<n;i++) keys[i]=scr(i);
    for(int i=0;i<n;i++) m.put(keys[i], 7);
    List<Integer> t0=new ArrayList<>(), t1=new ArrayList<>();
    for(Integer k:keys){ if(m.whichTable(k)==0) t0.add(k); else t1.add(k);}
    int q=1<<22; SplittableRandom r=new SplittableRandom(1);
    Integer[] q0=new Integer[q], q1=new Integer[q], qa=new Integer[q];
    for(int i=0;i<q;i++){q0[i]=t0.get(r.nextInt(t0.size())); q1[i]=t1.get(r.nextInt(t1.size())); qa[i]=keys[r.nextInt(n)];}
    double b0=1e9,b1=1e9,ba=1e9;
    for(int t=0;t<6;t++){ long s=0; long x=System.nanoTime(); for(Integer k:q0) s+=m.containsKey(k)?1:0; long y=System.nanoTime(); for(Integer k:q1) s+=m.containsKey(k)?1:0; long z=System.nanoTime(); for(Integer k:qa) s+=m.containsKey(k)?1:0; long w=System.nanoTime(); sink+=s;
      b0=Math.min(b0,(y-x)/(double)q); b1=Math.min(b1,(z-y)/(double)q); ba=Math.min(ba,(w-z)/(double)q);}
    System.out.printf("b=%d load=%.2f  in table 1: %.1f%%   hit-in-T0 %.1f ns   hit-in-T1 %.1f ns   all %.1f ns%n",b,m.loadFactor(),100.0*t1.size()/n,b0,b1,ba);
  }
}
