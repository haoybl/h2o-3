package water.fvec;

import water.DKV;
import water.H2O;
import water.Key;
import water.rapids.AST;
import water.rapids.ASTRow;
import water.rapids.Env;

/**
 * This wrapper pushes a transform down into each chunk so that
 * transformations will happen on-the-fly. When wrapped and there
 * are Op instances to be applied, the atd call will supersede the
 * usual chunk-at retrieval with a "special" atd call.
 *
 * Overhead added per element fetch per chunk is another virtual call
 * per Op per element (per Chunk). As has been noted (see e.g. RollupStats),
 * virtual calls are expensive, but the memory savings are substantial.
 *
 * AutoML can freely transform columns without ramification.
 *
 * Each wrapped Vec will track its own transformations, which makes it easy
 * when generating a POJO.
 *
 * A TransformWrappedVec is actually a function of one or more Vec instances.
 *
 * This class exists here so that Chunk and NewChunk don't need to become fully public
 * (since java has no friends). Other packages (not just core H2O) depend on this class!
 *
 *
 * @author spencer
 */
public class TransformWrappedVec extends WrappedVec {

  private final Key<Vec>[] _masterVecKeys;
  private transient Vec[] _masterVecs;
  private final AST _fun;

  public TransformWrappedVec(Key key, int rowLayout, AST fun, Key<Vec>[] masterVecKeys) {
    super(key, rowLayout, null);
    _fun=fun;
    _masterVecKeys = masterVecKeys;
    DKV.put(this);
  }

  @Override public Chunk chunkForChunkIdx(int cidx) {
    Chunk[] cs = new Chunk[_masterVecKeys.length];
    if( _masterVecs==null )
      _masterVecs = new Vec[_masterVecKeys.length];
    for(int i=0; i<cs.length;++i)
      cs[i] = (_masterVecs[i]!=null?_masterVecs[i]:(_masterVecs[i] = _masterVecKeys[i].get())).chunkForChunkIdx(cidx);
    return new TransformWrappedChunk(_fun, this, cs);
  }

  @Override public Vec doCopy() {
    Vec v = new TransformWrappedVec(group().addVec(), _rowLayout, _fun, _masterVecKeys);
    v.setDomain(domain()==null?null:domain().clone());
    return v;
  }

  public static class TransformWrappedChunk extends Chunk {
    public final AST _fun;
    public final transient Chunk _c[];

    private final AST[] _asts;
    private final double[] _ds;
    private final Env _env;

    TransformWrappedChunk(AST fun, Vec transformWrappedVec, Chunk[] c) {

      // set all the chunk fields
      _c = c; set_len(_c[0]._len);
      _start = _c[0]._start; _vec = transformWrappedVec; _cidx = _c[0]._cidx;

      _fun=fun;
      _ds = new double[_c.length];
      _asts = new AST[]{_fun,new ASTRow(_ds,null)};
      _env = new Env(null);
    }


    // applies the function to a row of doubles
    @Override public double atd_impl(int idx) {
      for(int i=0;i<_ds.length;++i)
        _ds[i] = _c[i].atd(idx);
      double[] valRow = _fun.apply(_env,_env.stk(),_asts).getRow(); // Make the call per-row
      return valRow[0];
    }

    @Override public long at8_impl(int idx) { throw H2O.unimpl(); }
    @Override public boolean isNA_impl(int idx) { return Double.isNaN(atd_impl(idx)); }  // ouch, not quick! runs thru atd_impl
    // Returns true if the masterVec is missing, false otherwise
    @Override public boolean set_impl(int idx, long l)   { return false; }
    @Override public boolean set_impl(int idx, double d) { return false; }
    @Override public boolean set_impl(int idx, float f)  { return false; }
    @Override public boolean setNA_impl(int idx)         { return false; }
    @Override public NewChunk inflate_impl(NewChunk nc) {
      nc.set_sparseLen(nc.set_len(0));
      for( int i=0; i< _len; i++ )
        if( isNA(i) ) nc.addNA();
        else          nc.addNum(atd(i));
      return nc;
    }
    @Override protected final void initFromBytes () { throw water.H2O.fail(); }
  }
}
