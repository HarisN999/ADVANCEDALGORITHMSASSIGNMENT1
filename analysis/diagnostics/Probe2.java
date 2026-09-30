package diagnostics;
import cuckoo.CuckooHashMap;
import java.util.*;
public class Probe2 {
  static long sink;
  static int scr(int x){x*=0x9E3779B1;x^=x>>>15;x*=0x2C1B3C6D;x^=x>>>12;x*=0x297A2D39;x^=x>>>15;return x;}
  public static void main(String[] a){
    int b=Integer.parseInt(a[0]); int n=Integer.parseInt(a[1]); double ml=Double.parseDouble(a[2]); String order=a[3];
    CuckooHashMap<Integer,Integer> m = CuckooHashMap.builder().bucketSize(b).maxLoad(ml).stash(4).expectedSize(n).build();
    Integer[] keys=new Integer[n]; for(int i=0;i<n;i++) keys[i]=scr(i);
    for(int i=0;i<n;i++) m.put(keys[i], 7);
    int q=1<<22; Integer[] hq=new Integer[q]; SplittableRandom r=new SplittableRandom(1);
    for(int i=0;i<q;i++) hq[i]= order.equals("seq")? keys[i%n] : keys[r.nextInt(n)];
    double best=1e9;
    for(int t=0;t<6;t++){ long t0=System.nanoTime(); long s=0;
      for(Integer k:hq){ s+= m.containsKey(k)?1:0;} long t1=System.nanoTime(); sink+=s; best=Math.min(best,(t1-t0)/(double)q);}
    System.out.printf("b=%d n=%d load=%.2f cap=%d order=%s  containsKey-hit best %.1f ns%n",b,n,m.loadFactor(),m.capacity(),order,best);
    if(sink==3)System.out.println(sink);
  }
}
