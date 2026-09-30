package diagnostics;
import cuckoo.CuckooHashMap;
import java.util.*;
public class Probe4 {
  static long sink;
  static int scr(int x){x*=0x9E3779B1;x^=x>>>15;x*=0x2C1B3C6D;x^=x>>>12;x*=0x297A2D39;x^=x>>>15;return x;}
  public static void main(String[] a){
    int b=Integer.parseInt(a[0]); int slots=Integer.parseInt(a[1]);
    for(String ls: a[2].split(",")){
      double load=Double.parseDouble(ls); int n=(int)(load*slots);
      CuckooHashMap<Integer,Integer> m = CuckooHashMap.builder().bucketSize(b).maxLoad(1.0).stash(4).maxKicks(2000).bucketsPerTable(slots/(2*b)).build();
      Integer[] keys=new Integer[n]; for(int i=0;i<n;i++) keys[i]=scr(i);
      for(int i=0;i<n;i++) m.put(keys[i], 7);
      int q=1<<22; Integer[] hq=new Integer[q], mq=new Integer[q]; SplittableRandom r=new SplittableRandom(1);
      Integer[] absent=new Integer[n]; for(int i=0;i<n;i++) absent[i]=scr(n+i);
      for(int i=0;i<q;i++){ hq[i]=keys[r.nextInt(n)]; mq[i]=absent[r.nextInt(n)]; }
      double bh=1e9,bm=1e9;
      for(int t=0;t<5;t++){ long s=0,x=System.nanoTime(); for(Integer k:hq) s+=m.containsKey(k)?1:0; long y=System.nanoTime(); for(Integer k:mq) s+=m.containsKey(k)?1:0; long z=System.nanoTime(); sink+=s; bh=Math.min(bh,(y-x)/(double)q); bm=Math.min(bm,(z-y)/(double)q);}
      System.out.printf("b=%d slots=%d load=%.2f cap=%d  hit %.1f  miss %.1f%n",b,slots,m.loadFactor(),m.capacity(),bh,bm);
    }
  }
}
