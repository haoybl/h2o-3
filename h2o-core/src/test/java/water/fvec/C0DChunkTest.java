package water.fvec;

import org.junit.*;

import water.TestUtil;
import java.util.Arrays;

public class C0DChunkTest extends TestUtil {
  @BeforeClass() public static void setup() { stall_till_cloudsize(1); }
  @Test
  public void test_inflate_impl() {
    final int K = 1<<16;
    for (Double d : new Double[]{3.14159265358, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.MAX_VALUE, Double.NaN}) {
      NewChunk nc = new NewChunk(null, 0);
      for (int i=0;i<K;++i) nc.addNum(d);
      Assert.assertEquals(K, nc._len);
      Assert.assertEquals(Double.isNaN(d) ? 0: K, nc._sparseLen);
      Assert.assertEquals(K, nc.sparseLenZero());
      Assert.assertEquals(Double.isNaN(d) ? 0: K, nc.sparseLenNA());

      Chunk cc = nc.compress();
      Assert.assertEquals(K, cc._len);
      Assert.assertTrue(cc instanceof C0DChunk);
      for (int i=0;i<K;++i) Assert.assertEquals(d, cc.atd(i), Math.ulp(d));
      for (int i=0;i<K;++i) Assert.assertEquals(d, cc.at_abs(i), Math.ulp(d));

      nc = cc.inflate_impl(new NewChunk(null, 0));
      nc.values(0, nc._len);
      Assert.assertEquals(K, nc._len);
      Assert.assertEquals(Double.isNaN(d) ? 0: K, nc._sparseLen);
      Assert.assertEquals(K, nc.sparseLenZero());
      Assert.assertEquals(Double.isNaN(d) ? 0: K, nc.sparseLenNA());
      for (int i=0;i<K;++i) Assert.assertEquals(d, nc.atd(i), Math.ulp(d));
      for (int i=0;i<K;++i) Assert.assertEquals(d, nc.at_abs(i), Math.ulp(d));

      Chunk cc2 = nc.compress();
      Assert.assertEquals(K, cc2._len);
      Assert.assertTrue(cc2 instanceof C0DChunk);
      for (int i=0;i<K;++i) Assert.assertEquals(d, cc2.atd(i), Math.ulp(d));
      for (int i=0;i<K;++i) Assert.assertEquals(d, cc2.at_abs(i), Math.ulp(d));

      Assert.assertTrue(Arrays.equals(cc._mem, cc2._mem));
    }
  }
}
