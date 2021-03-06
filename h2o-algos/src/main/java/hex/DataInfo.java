package hex;

import water.*;
import water.fvec.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;

/**
* Created by tomasnykodym on 1/29/15.
 *
 * Provides higher level interface for accessing data row-wise.
 *
 * Performs on the fly auto-expansion of categorical variables (to 1 hot encoding) and standardization ( or normalize/demean/descale/none) of predictors and response.
 * Supports sparse data, sparse columns can be transformed to sparse rows on the fly with some (significant) memory overhead,
 * as the data of the whole chunk(s) will be copied.
 *
*/
public class DataInfo extends Keyed<DataInfo> {
  public int [] _activeCols;
  public Frame _adaptedFrame;  // the modified DataInfo frame (columns sorted by largest categorical -> least then all numerical columns)
  public int _responses;   // number of responses
  public int _outpus; // number of outputs

  public Vec setWeights(String name, Vec vec) {
    if(_weights)
      return _adaptedFrame.replace(weightChunkId(),vec);
    _adaptedFrame.insertVec(weightChunkId(),name,vec);
    _weights = true;
    return null;
  }

  public void dropWeights() {
    if(!_weights)return;
    _adaptedFrame.remove(weightChunkId());
    _weights = false;
  }

  public void dropInteractions() { // only called to cleanup the InteractionWrappedVecs!
    if(_interactions!=null) {
      Vec[] vecs = _adaptedFrame.remove(_interactionVecs);
      for(Vec v:vecs)v.remove();
      _interactions = null;
    }
  }

  public int[] activeCols() {
    if(_activeCols != null) return _activeCols;
    int [] res = new int[fullN()+1];
    for(int i = 0; i < res.length; ++i)
      res[i] = i;
    return res;
  }

  public void addResponse(String [] names, Vec[] vecs) {
    _adaptedFrame.add(names,vecs);
    _responses += vecs.length;
  }

  public enum TransformType {
    NONE, STANDARDIZE, NORMALIZE, DEMEAN, DESCALE;

    public boolean isMeanAdjusted(){
      switch(this){
        case NONE:
        case DESCALE:
        case NORMALIZE:
          return false;
        case STANDARDIZE:
        case DEMEAN:
          return true;
        default:
          throw H2O.unimpl();
      }
    }
    public boolean isSigmaScaled(){
      switch(this){
        case NONE:
        case DEMEAN:
        case NORMALIZE:
          return false;
        case STANDARDIZE:
        case DESCALE:
          return true;
        default:
          throw H2O.unimpl();
      }
    }
  }
  public TransformType _predictor_transform;
  public TransformType _response_transform;
  public boolean _useAllFactorLevels;
  public int _nums;  // "raw" number of numerical columns as they exist in the frame
  public int _cats;  // "raw" number of categorical columns as they exist in the frame
  public int [] _catOffsets;   // offset column indices for the 1-hot expanded values (includes enum-enum interaction)
  public boolean [] _catMissing;  // bucket for missing levels
  public int [] _catModes;    // majority class of each categorical col
  public int [] _permutation; // permutation matrix mapping input col indices to adaptedFrame
  public double [] _normMul;  // scale the predictor column by this value
  public double [] _normSub;  // subtract from the predictor this value
  public double [] _normRespMul;  // scale the response column by this value
  public double [] _normRespSub;  // subtract from the response column this value
  public double [] _numMeans;
  public boolean _intercept = true;
  public boolean _offset;
  public boolean _weights;
  public boolean _fold;
  public InteractionPair[] _interactions; // raw set of interactions
  public int _interactionVecs[]; // the interaction columns appearing in _adaptedFrame
  public int[] _numOffsets; // offset column indices used by numerical interactions: total number of numerical columns is given by _numOffsets[_nums] - _numOffsets[0]
  public int responseChunkId(int n){return n + _cats + _nums + (_weights?1:0) + (_offset?1:0) + (_fold?1:0);}
  public int foldChunkId(){return _cats + _nums + (_weights?1:0) + (_offset?1:0);}

  public int offsetChunkId(){return _cats + _nums + (_weights ?1:0);}
  public int weightChunkId(){return _cats + _nums;}
  public int outputChunkId() { return outputChunkId(0);}
  public int outputChunkId(int n) { return n + _cats + _nums + (_weights?1:0) + (_offset?1:0) + (_fold?1:0) + _responses;}
  public void addOutput(String name, Vec v) {_adaptedFrame.add(name,v);}
  public Vec getOutputVec(int i) {return _adaptedFrame.vec(outputChunkId(i));}
  public void setResponse(String name, Vec v){ setResponse(name,v,0);}
  public void setResponse(String name, Vec v, int n){ _adaptedFrame.insertVec(responseChunkId(n),name,v);}

  public final boolean _skipMissing;
  public final boolean _imputeMissing;
  public boolean _valid; // DataInfo over validation data set, can have unseen (unmapped) categorical levels
  public final int [][] _catLvls;

  private DataInfo() {  _catLvls = null; _skipMissing = true; _imputeMissing = false; _valid = false; _offset = false; _weights = false; _fold = false; }
  public String[] _coefNames;
  @Override protected long checksum_impl() {throw H2O.unimpl();} // don't really need checksum

  public DataInfo deep_clone() {
    AutoBuffer ab = new AutoBuffer();
    this.write(ab);
    ab.flipForReading();
    return new DataInfo().read(ab);
  }

  // Modify the train & valid frames directly; sort the categorical columns
  // up front according to size; compute the mean/sigma for each column for
  // later normalization.
  public DataInfo(Frame train, Frame valid, boolean useAllFactorLevels, TransformType predictor_transform, boolean skipMissing, boolean imputeMissing, boolean missingBucket) {
    this(train, valid, 0, useAllFactorLevels, predictor_transform, TransformType.NONE, skipMissing, imputeMissing, missingBucket, /* weight */ false, /* offset */ false, /* fold */ false, /* intercept */ false);
  }

  public DataInfo(Frame train, Frame valid, int nResponses, boolean useAllFactorLevels, TransformType predictor_transform, TransformType response_transform, boolean skipMissing, boolean imputeMissing, boolean missingBucket, boolean weight, boolean offset, boolean fold) {
    this(train,valid,nResponses,useAllFactorLevels,predictor_transform,response_transform,skipMissing,imputeMissing,missingBucket,weight,offset,fold,null);
  }


  /**
   *
   * The train/valid Frame instances are sorted by categorical (themselves sorted by
   * cardinality greatest to least) with all numerical columns following. The response
   * column(s) are placed at the end.
   *
   *
   * Interactions:
   *  1. Num-Num (Note: N(0,1) * N(0,1) ~ N(0,1) )
   *  2. Num-Enum
   *  3. Enum-Enum
   *
   *  Interactions are produced on the fly and are dense (in all 3 cases). Consumers of
   *  DataInfo should not have to care how these interactions are generated. Any heuristic
   *  using the fullN value should continue functioning the same.
   *
   *  Interactions are specified in two ways:
   *    A. As a list of pairs of column indices.
   *    B. As a list of pairs of column indices with limited enums.
   */
  public DataInfo(Frame train, Frame valid, int nResponses, boolean useAllFactorLevels, TransformType predictor_transform, TransformType response_transform, boolean skipMissing, boolean imputeMissing, boolean missingBucket, boolean weight, boolean offset, boolean fold, InteractionPair[] interactions) {
    super(Key.<DataInfo>make());
    _valid = valid != null;
    assert predictor_transform != null;
    assert  response_transform != null;
    _offset = offset;
    _weights = weight;
    _fold = fold;
    assert !(skipMissing && imputeMissing) : "skipMissing and imputeMissing cannot both be true";
    _skipMissing = skipMissing;
    _imputeMissing = imputeMissing;
    _predictor_transform = predictor_transform;
    _response_transform = response_transform;
    _responses = nResponses;
    _useAllFactorLevels = useAllFactorLevels;
    _interactions=interactions;

    // create dummy InteractionWrappedVecs and shove them onto the front
    if( _interactions!=null ) {
      _interactionVecs=new int[_interactions.length];
      train = makeInteractions(train,false,_interactions,_useAllFactorLevels, _skipMissing).add(train);
      if( valid!=null )
        valid = makeInteractions(valid,true,_interactions,_useAllFactorLevels, _skipMissing).add(valid);
    }

    _permutation = new int[train.numCols()];
    final Vec[] tvecs = train.vecs();

    // Count categorical-vs-numerical
    final int n = tvecs.length-_responses - (offset?1:0) - (weight?1:0) - (fold?1:0);
    int [] nums = MemoryManager.malloc4(n);
    int [] cats = MemoryManager.malloc4(n);
    int nnums = 0, ncats = 0;
    for(int i = 0; i < n; ++i)
      if (tvecs[i].isCategorical())
        cats[ncats++] = i;
      else
        nums[nnums++] = i;

    _nums = nnums;
    _cats = ncats;
    _catLvls = new int[ncats][];

    // sort the cats in the decreasing order according to their size
    for(int i = 0; i < ncats; ++i)
      for(int j = i+1; j < ncats; ++j)
        if( tvecs[cats[i]].domain().length < tvecs[cats[j]].domain().length ) {
          int x = cats[i];
          cats[i] = cats[j];
          cats[j] = x;
        }
    String[] names = new String[train.numCols()];
    Vec[] tvecs2 = new Vec[train.numCols()];

    // Compute the cardinality of each cat
    _catModes = new int[ncats];
    _catOffsets = MemoryManager.malloc4(ncats+1);
    _catMissing = new boolean[ncats];
    int len = _catOffsets[0] = 0;
    int interactionIdx=0; // simple index into the _interactionVecs array
    for(int i = 0; i < ncats; ++i) {
      names[i]  =   train._names[cats[i]];
      Vec v = (tvecs2[i] = tvecs[cats[i]]);
      _catMissing[i] = missingBucket; //needed for test time
      if( v instanceof InteractionWrappedVec ) {
        _interactionVecs[interactionIdx++]=i;  // i (and not cats[i]) because this is the index in _adaptedFrame
        _catOffsets[i + 1] = (len += v.domain().length + (missingBucket ? 1 : 0));
      }
      else
        _catOffsets[i+1] = (len += v.domain().length - (useAllFactorLevels?0:1) + (missingBucket? 1 : 0)); //missing values turn into a new factor level
      _catModes[i] = imputeMissing?imputeCat(train.vec(cats[i])):_catMissing[i]?v.domain().length:-100;
      _permutation[i] = cats[i];
    }
    _numMeans = new double[nnums];
    _numOffsets = MemoryManager.malloc4(nnums+1);
    _numOffsets[0]=len;
    boolean isIWV; // is InteractionWrappedVec?
    for(int i = 0; i < nnums; ++i) {
      names[i+ncats] = train._names[nums[i]];
      Vec v = train.vec(nums[i]);
      tvecs2[i+ncats] = v;
      isIWV = v instanceof InteractionWrappedVec;
      if( isIWV ) _interactionVecs[interactionIdx++]=i+ncats;
      _numOffsets[i+1] = (len+= (isIWV ? ((InteractionWrappedVec) v).expandedLength() : 1));
      _numMeans[i] = train.vec(nums[i]).mean();
      _permutation[i+ncats] = nums[i];
    }
    for(int i = names.length-nResponses - (weight?1:0) - (offset?1:0) - (fold?1:0); i < names.length; ++i) {
      names[i] = train._names[i];
      tvecs2[i] = train.vec(i);
    }
    _adaptedFrame = new Frame(names,tvecs2);
    train.restructure(names,tvecs2);
    if (valid != null)
      valid.restructure(names,valid.vecs(names));
//    _adaptedFrame = train;

    setPredictorTransform(predictor_transform);
    if(_responses > 0)
      setResponseTransform(response_transform);
  }

  public static Frame makeInteractions(Frame fr, boolean valid, InteractionPair[] interactions, boolean useAllFactorLevels, boolean skipMissing) {
    Vec anyTrainVec = fr.anyVec();
    Vec[] interactionVecs = new Vec[interactions.length];
    String[] interactionNames  = new String[interactions.length];
    int idx = 0;
    for (InteractionPair ip : interactions) {
      interactionNames[idx] = fr.name(ip._v1) + "_" + fr.name(ip._v2);
      InteractionWrappedVec iwv =new InteractionWrappedVec(anyTrainVec.group().addVec(), anyTrainVec._rowLayout, ip._v1Enums, ip._v2Enums, useAllFactorLevels, skipMissing, fr.vec(ip._v1)._key, fr.vec(ip._v2)._key);
      if(!valid) ip.setDomain(iwv.domain());
      interactionVecs[idx++] = iwv;
    }
    return new Frame(interactionNames, interactionVecs);
  }


  /**
   * This class represents a pair of interacting columns plus some additional data
   * about specific enums to be interacted when the vecs are categorical. The question
   * naturally arises why not just use something like an ArrayList of int[2] (as is done,
   * for example, in the Interaction/CreateInteraction classes) and the answer essentially
   * boils down a desire to specify these specific levels.
   *
   * Another difference with the CreateInteractions class:
   *  1. do not interact on NA (someLvl_NA  and NA_somLvl are actual NAs)
   *     this does not appear here, but in the InteractionWrappedVec class
   *  TODO: refactor the CreateInteractions to be useful here and in InteractionWrappedVec
   */
  public static class InteractionPair extends Iced {
    private int _v1,_v2;

    private String[] _domain; // not null for enum-enum interactions
    private String[] _v1Enums;
    private String[] _v2Enums;
    private int _hash;
    private InteractionPair() {}
    private InteractionPair(int v1, int v2, String[] v1Enums, String[] v2Enums) {
      _v1=v1;_v2=v2;_v1Enums=v1Enums;_v2Enums=v2Enums;
      // hash is column ints; Item 9 p.47 of Effective Java
      _hash=17;
      _hash = 31*_hash + _v1;
      _hash = 31*_hash + _v2;
      if( _v1Enums==null ) _hash = 31*_hash;
      else
        for( String s:_v1Enums ) _hash = 31*_hash + s.hashCode();
      if( _v2Enums==null ) _hash = 31*_hash;
      else
        for( String s:_v2Enums ) _hash = 31*_hash + s.hashCode();
    }

    /**
     * Generate all pairwise combinations of ints in the range [from,to).
     * @param from Start index
     * @param to End index (exclusive)
     * @return An array of interaction pairs.
     */
    public static InteractionPair[] generatePairwiseInteractions(int from, int to) {
      if( 1==(to-from) )
        throw new IllegalArgumentException("Illegal range of values, must be greater than a single value. Got: " + from + "<" + to);
      InteractionPair[] res = new InteractionPair[ ((to-from-1)*(to-from)) >> 1];  // n*(n+1) / 2
      int idx=0;
      for(int i=from;i<to;++i)
        for(int j=i+1;j<to;++j)
          res[idx++] = new InteractionPair(i,j,null,null);
      return res;
    }

    /**
     * Generate all pairwise combinations of the arguments.
     * @param indexes An array of column indices.
     * @return An array of interaction pairs
     */
    public static InteractionPair[] generatePairwiseInteractions(int... indexes) {
      if( indexes.length < 2 )
        throw new IllegalArgumentException("Must supply 2 or more columns.");
      InteractionPair[] res = new InteractionPair[ (indexes.length-1)*(indexes.length)>>1]; // n*(n+1) / 2
      int idx=0;
      for(int i=0;i<indexes.length;++i)
        for(int j=i+1;j<indexes.length;++j)
          res[idx++] = new InteractionPair(indexes[i],indexes[j],null,null);
      return res;
    }

    /**
     * Set the domain; computed in an MRTask over the two categorical vectors that make
     * up this interaction pair
     * @param dom The domain retrieved by the CombineDomainTask in InteractionWrappedVec
     */
    public void setDomain(String[] dom) { _domain=dom; }

    // parser stuff
    private int _p;
    private String _str;
    public static InteractionPair[] read(String interaction) {
      String[] interactions=interaction.split("\n");
      HashSet<InteractionPair> res=new HashSet<>();
      for(String i: interactions)
        res.addAll(new InteractionPair().parse(i));
      return res.toArray(new InteractionPair[res.size()]);
    }

    private HashSet<InteractionPair> parse(String i) { // v1[E8,E9]:v2,v3,v8,v90,v128[E1,E22]
      _p=0;
      _str=i;
      HashSet<InteractionPair> res=new HashSet<>();
      int v1 = parseNum();    // parse the first int
      String[] v1Enums=parseEnums();  // shared
      if( i.charAt(_p)!=':' || _p>=i.length() ) throw new IllegalArgumentException("Error");
      while( _p++<i.length() ) {
        int v2=parseNum();
        String[] v2Enums=parseEnums();
        if( v1 == v2 ) continue; // don't interact on self!
        res.add(new InteractionPair(v1,v2,v1Enums,v2Enums));
      }
      return res;
    }

    private int parseNum() {
      int start=_p++;
      while( _p<_str.length() && '0' <= _str.charAt(_p) && _str.charAt(_p) <= '9') _p++;
      try {
        return Integer.valueOf(_str.substring(start,_p));
      } catch(NumberFormatException ex) {
        throw new IllegalArgumentException("No number could be parsed. Interaction: " + _str);
      }
    }

    private String[] parseEnums() {
      if( _p>=_str.length() || _str.charAt(_p)!='[' ) return null;
      ArrayList<String> enums = new ArrayList<>();
      while( _str.charAt(_p++)!=']' ) {
        int start=_p++;
        while(_str.charAt(_p)!=',' && _str.charAt(_p)!=']') _p++;
        enums.add(_str.substring(start,_p));
      }
      return enums.toArray(new String[enums.size()]);
    }

    @Override public int hashCode() { return _hash; }
    @Override public String toString() { return _v1+(_v1Enums==null?"":Arrays.toString(_v1Enums))+":"+_v2+(_v2Enums==null?"":Arrays.toString(_v2Enums)); }
    @Override public boolean equals( Object o ) {
      boolean res = o instanceof InteractionPair;
      if (res) {
        InteractionPair ip = (InteractionPair) o;
        return (_v1 == ip._v1) && (_v2 == ip._v2) && Arrays.equals(_v1Enums, ip._v1Enums) && Arrays.equals(_v2Enums, ip._v2Enums);
      }
      return false;
    }
  }

  public DataInfo(Frame train, Frame valid, int nResponses, boolean useAllFactorLevels, TransformType predictor_transform, TransformType response_transform, boolean skipMissing, boolean imputeMissing, boolean missingBucket, boolean weight, boolean offset, boolean fold, boolean intercept) {
    this(train, valid, nResponses, useAllFactorLevels, predictor_transform, response_transform, skipMissing, imputeMissing, missingBucket, weight, offset, fold);
    _intercept = intercept;
  }

  public DataInfo validDinfo(Frame valid) {
    DataInfo res = new DataInfo(_adaptedFrame,null,1,_useAllFactorLevels,TransformType.NONE,TransformType.NONE,_skipMissing,_imputeMissing,!(_skipMissing || _imputeMissing),_weights,_offset,_fold);
    res._adaptedFrame = new Frame(_adaptedFrame.names(),valid.vecs(_adaptedFrame.names()));
    res._valid = true;
    return res;
  }

  public DataInfo scoringInfo(){
    DataInfo res = new DataInfo(_adaptedFrame,null,1,_useAllFactorLevels,TransformType.NONE,TransformType.NONE,_skipMissing,_imputeMissing,!_skipMissing,_weights,_offset,_fold);
    res._adaptedFrame = null;
    res._weights = false;
    res._offset = false;
    res._fold = false;
    res._responses = 0;
    res._valid = true;
    res._interactions=_interactions;
    res._interactionVecs=_interactionVecs;
    return res;
  }

  public double[] denormalizeBeta(double [] beta) {
    int N = fullN()+1;
    assert (beta.length % N) == 0:"beta len = " + beta.length + " expected multiple of" + N;
    int nclasses = beta.length/N;
    beta = MemoryManager.arrayCopyOf(beta,beta.length);
    if (_predictor_transform == DataInfo.TransformType.STANDARDIZE) {
      for(int c = 0; c < nclasses; ++c) {
        int off = N*c;
        double norm = 0.0;        // Reverse any normalization on the intercept
        // denormalize only the numeric coefs (categoricals are not normalized)
        final int numoff = numStart();
        for (int i = numoff; i < N-1; i++) {
          double b = beta[off + i] * _normMul[i - numoff];
          norm += b * _normSub[i - numoff]; // Also accumulate the intercept adjustment
          beta[off + i] = b;
        }
        beta[off + N - 1] -= norm;
      }
    }
    return beta;
  }

  private int [] _fullCatOffsets;

  protected int [] fullCatOffsets(){ return _fullCatOffsets == null?_catOffsets:_fullCatOffsets;}
  // private constructor called by filterExpandedColumns
  private DataInfo(DataInfo dinfo,Frame fr, double [] normMul, double [] normSub, int[][] catLevels, int [] catModes){
    _fullCatOffsets = dinfo._catOffsets;
    if(!dinfo._useAllFactorLevels) {
      _fullCatOffsets = dinfo._catOffsets.clone();
      for (int i = 0; i < _fullCatOffsets.length; ++i)
        _fullCatOffsets[i] += i; // add for the skipped zeros.
    }
    _offset = dinfo._offset;
    _weights = dinfo._weights;
    _fold = dinfo._fold;
    _valid = false;
    _interactions = dinfo._interactions;
    _interactionVecs=dinfo._interactionVecs;
    assert dinfo._predictor_transform != null;
    assert  dinfo._response_transform != null;
    _predictor_transform = dinfo._predictor_transform;
    _response_transform  =  dinfo._response_transform;
    _skipMissing = dinfo._skipMissing;
    _imputeMissing = dinfo._imputeMissing;
    _adaptedFrame = fr;
    _catOffsets = MemoryManager.malloc4(catLevels.length + 1);
    _catMissing = new boolean[catLevels.length];
    Arrays.fill(_catMissing,!(dinfo._imputeMissing || dinfo._skipMissing));
    int s = 0;
    for(int i = 0; i < catLevels.length; ++i){
      _catOffsets[i] = s;
      s += catLevels[i].length;
    }
    _catLvls = catLevels;
    _catOffsets[_catOffsets.length-1] = s;
    _responses = dinfo._responses;
    _cats = catLevels.length;
    _nums = fr.numCols()-_cats - dinfo._responses - (_offset?1:0) - (_weights?1:0) - (_fold?1:0);
    _numOffsets = _nums==0?new int[0]:dinfo._numOffsets;
    _useAllFactorLevels = true;//dinfo._useAllFactorLevels;
    _numMeans = new double[_nums];
    _normMul = normMul;
    _normSub = normSub;
    _catModes = catModes;
    for(int i = 0; i < _nums; i++)
      _numMeans[i] = _adaptedFrame.vec(_cats+i).mean();
  }

  public static int imputeCat(Vec v) {
    if(v.isCategorical()) return v.mode();
    return (int)Math.round(v.mean());
  }


  public DataInfo filterExpandedColumns(int [] cols){    assert _predictor_transform != null;
    assert  _response_transform != null;
    if(cols == null)return deep_clone();
    int hasIcpt = (cols.length > 0 && cols[cols.length-1] == fullN())?1:0;
    int i = 0, j = 0, ignoredCnt = 0;
    //public DataInfo(Frame fr, int hasResponses, boolean useAllFactorLvls, double [] normSub, double [] normMul, double [] normRespSub, double [] normRespMul){
    int [][] catLvls = new int[_cats][];
    int [] ignoredCols = MemoryManager.malloc4(_nums + _cats);
    // first do categoricals...
    if(_catOffsets != null) {
      int coff = _useAllFactorLevels?0:1;
      while (i < cols.length && cols[i] < _catOffsets[_catOffsets.length - 1]) {
        int[] levels = MemoryManager.malloc4(_catOffsets[j + 1] - _catOffsets[j]);
        int k = 0;
        while (i < cols.length && cols[i] < _catOffsets[j + 1])
          levels[k++] = (cols[i++] - _catOffsets[j]) + coff;
        if (k > 0)
          catLvls[j] = Arrays.copyOf(levels, k);
        ++j;
      }
    }
    int [] catModes = _catModes;
    for(int k =0; k < catLvls.length; ++k)
      if(catLvls[k] == null)ignoredCols[ignoredCnt++] = k;
    if(ignoredCnt > 0){
      int [][] cs = new int[_cats-ignoredCnt][];
      catModes = new int[_cats-ignoredCnt];
      int y = 0;
      for (int c = 0; c < catLvls.length; ++c) if (catLvls[c] != null) {
        catModes[y] = _catModes[c];
        cs[y++] = catLvls[c];
      }
      assert y == cs.length;
      catLvls = cs;
    }
    // now numerics
    int prev = j = 0;
    for(; i < cols.length; ++i){
      for(int k = prev; k < (cols[i]-numStart()); ++k ){
        ignoredCols[ignoredCnt++] = k+_cats;
        ++j;
      }
      prev = ++j;
    }
    for(int k = prev; k < _nums; ++k)
      ignoredCols[ignoredCnt++] = k+_cats;
    Frame f = new Frame(_adaptedFrame.names().clone(),_adaptedFrame.vecs().clone());
    if(ignoredCnt > 0) f.remove(Arrays.copyOf(ignoredCols,ignoredCnt));
    assert catLvls.length < f.numCols():"cats = " + catLvls.length + " numcols = " + f.numCols();
    double [] normSub = null;
    double [] normMul = null;
    int id = Arrays.binarySearch(cols,numStart());
    if(id < 0) id = -id-1;
    int nnums = cols.length - id - hasIcpt;
    int off = numStart();
    if(_normSub != null) {
      normSub = new double[nnums];
      for(int k = id; k < (id + nnums); ++k)
        normSub[k-id] = _normSub[cols[k]-off];
    }
    if(_normMul != null) {
      normMul = new double[nnums];
      for(int k = id; k < (id + nnums); ++k)
        normMul[k-id] = _normMul[cols[k]-off];
    }
    // public DataInfo(Frame train, Frame valid, int nResponses, boolean useAllFactorLevels, TransformType predictor_transform, TransformType response_transform, boolean skipMissing, boolean imputeMissing, boolean missingBucket, boolean weight, boolean offset, boolean fold) {
    DataInfo dinfo = new DataInfo(this,f, normMul, normSub, catLvls, catModes);
    dinfo._activeCols = cols;
    return dinfo;
  }

  public void updateWeightedSigmaAndMean(double [] sigmas, double [] mean) {
    if(_predictor_transform.isSigmaScaled()) {
      if(sigmas.length != _normMul.length)
        throw new IllegalArgumentException("Length of sigmas does not match number of scaled columns.");
      for(int i = 0; i < sigmas.length; ++i)
        _normMul[i] = sigmas[i] != 0?1.0/sigmas[i]:1;
    }
    if(_predictor_transform.isMeanAdjusted()) {
      if(mean.length != _normSub.length)
        throw new IllegalArgumentException("Length of means does not match number of scaled columns.");
      System.arraycopy(mean,0,_normSub,0,mean.length);
    }
  }
  public void updateWeightedSigmaAndMeanForResponse(double [] sigmas, double [] mean) {
    if(_response_transform.isSigmaScaled()) {
      if(sigmas.length != _normRespMul.length)
        throw new IllegalArgumentException("Length of sigmas does not match number of scaled columns.");
      for(int i = 0; i < sigmas.length; ++i)
        _normRespMul[i] = sigmas[i] != 0?1.0/sigmas[i]:1;
    }
    if(_response_transform.isMeanAdjusted()) {
      if(mean.length != _normRespSub.length)
        throw new IllegalArgumentException("Length of means does not match number of scaled columns.");
      System.arraycopy(mean,0,_normRespSub,0,mean.length);
    }
  }

  private void setTransform(TransformType t, double [] normMul, double [] normSub, int vecStart, int n) {
    for (int i = 0; i < n; ++i) {
      Vec v = _adaptedFrame.vec(vecStart + i);
      switch (t) {
        case STANDARDIZE:
          normMul[i] = (v.sigma() != 0)?1.0/v.sigma():1.0;
          normSub[i] = v.mean();
          break;
        case NORMALIZE:
          normMul[i] = (v.max() - v.min() > 0)?1.0/(v.max() - v.min()):1.0;
          normSub[i] = v.mean();
          break;
        case DEMEAN:
          normMul[i] = 1;
          normSub[i] = v.mean();
          break;
        case DESCALE:
          normMul[i] = (v.sigma() != 0)?1.0/v.sigma():1.0;
          normSub[i] = 0;
          break;
        default:
          throw H2O.unimpl();
      }
      assert !Double.isNaN(normMul[i]);
      assert !Double.isNaN(normSub[i]);
    }
  }
  public void setPredictorTransform(TransformType t){
    _predictor_transform = t;
    if(t == TransformType.NONE) {
      _normMul = null;
      _normSub = null;
    } else {
      _normMul = MemoryManager.malloc8d(_nums);
      _normSub = MemoryManager.malloc8d(_nums);
      setTransform(t,_normMul,_normSub,_cats,_nums);
    }
  }

  public void setResponseTransform(TransformType t){
    _response_transform = t;
    if(t == TransformType.NONE) {
      _normRespMul = null;
      _normRespSub = null;
    } else {
      _normRespMul = MemoryManager.malloc8d(_responses);
      _normRespSub = MemoryManager.malloc8d(_responses);
      setTransform(t,_normRespMul,_normRespSub,_adaptedFrame.numCols()-_responses,_responses);
    }
  }

  public boolean isInteractionVec(int colid) {
    if( null==_interactions || null==_interactionVecs ) return false;
    if( _adaptedFrame!=null )
      return _adaptedFrame.vec(colid) instanceof InteractionWrappedVec;
    else
      return Arrays.binarySearch(_interactionVecs,colid) > 0;
  }

  /**
   *
   * Get the fully expanded number of predictor columns.
   * Note that this value does not include:
   *  response column(s)
   *  weight column
   *  offset column
   *  fold column
   *
   * @return expanded number of columns in the underlying frame
   */
  public final int fullN()     { return numNums() + numCats();      }
  public final int largestCat(){ return _cats > 0?_catOffsets[1]:0; }
  public final int numStart()  { return _catOffsets[_cats];         }
  public final int numCats()   { return _catOffsets[_cats];         }
  public final int numNums()   { return _interactions!=null?(_numOffsets[_numOffsets.length-1]-numStart()):_nums; }
  public final String[] coefNames() {
    if (_coefNames != null) return _coefNames; 
    int k = 0;
    final int n = fullN();
    String [] res = new String[n];
    final Vec [] vecs = _adaptedFrame.vecs();

    // first do all of the expanded categorical names
    for(int i = 0; i < _cats; ++i) {
      for (int j = (_useAllFactorLevels || vecs[i] instanceof InteractionWrappedVec) ? 0 : 1; j < vecs[i].domain().length; ++j) {
        int jj = getCategoricalId(i, j);
        if(jj < 0)
          continue;
        res[k++] = _adaptedFrame._names[i] + "." + vecs[i].domain()[j];
      }
      if (_catMissing[i] && getCategoricalId(i,_catModes[i]) >=0) res[k++] = _adaptedFrame._names[i] + ".missing(NA)";
    }
    // now loop over the numerical columns, collecting up any expanded InteractionVec names
    if( _interactions==null ) {
      final int nums = n-k;
      System.arraycopy(_adaptedFrame._names, _cats, res, k, nums);
    } else {
      for (int i = _cats; i < _nums; ++i) {
        InteractionWrappedVec v;
        if (vecs[i] instanceof InteractionWrappedVec && ((v = (InteractionWrappedVec) vecs[i]).domains() != null)) { // in this case, get the categoricalOffset
          for (int j = 0; k < v.domains().length; ++j) {
            if (getCategoricalIdFromInteraction(i, j) < 0) continue;
            res[k++] = _adaptedFrame._names[i] + "." + v.domains()[j];
          }
        } else
          res[k++] = _adaptedFrame._names[i];
      }
    }
    _coefNames = res;
    return res;
  }

  // Return permutation matrix mapping input names to adaptedFrame colnames
  public int[] mapNames(String[] names) {
    assert names.length == _adaptedFrame._names.length : "Names must be the same length!";
    int[] idx = new int[names.length];
    Arrays.fill(idx, -1);

    for(int i = 0; i < _adaptedFrame._names.length; i++) {
      for(int j = 0; j < names.length; j++) {
        if( names[j].equals(_adaptedFrame.name(i)) ) {
          idx[i] = j; break;
        }
      }
    }
    return idx;
  }

  /**
   * Undo the standardization/normalization of numerical columns
   * @param in input values
   * @param out output values (can be the same as input)
   */
  public final void unScaleNumericals(double[] in, double[] out) {
    if (_nums == 0) return;
    assert (in.length == out.length);
    assert (in.length == fullN());
    for (int k=numStart(); k < fullN(); ++k) {
      double m = _normMul == null ? 1f : _normMul[k-numStart()];
      double s = _normSub == null ? 0f : _normSub[k-numStart()];
      out[k] = in[k] / m + s;
    }
  }

  public final class Row extends Iced {
    public boolean bad;       // should the row be skipped (GLM skip NA for example)
    public double [] numVals; // the backing data of the row
    public double [] response;
    public int    [] numIds;  // location of next sparse value
    public int    [] binIds;  // location of categorical
    public long      rid;     // row number (sometimes within chunk, or absolute)
    public int      cid;      // categorical id
    public int       nBins;   // number of enum    columns (not expanded)
    public int       nNums;   // number of numeric columns (not expanded)
    public int       nOutpus;
    public double    offset = 0;
    public double    weight = 1;
    private C8DChunk [] _outputs;


    public void setOutput(int i, double v) {_outputs[i].set8D(cid,v);}
    public double getOutput(int i) {return _outputs[i].get8D(cid);}
    public final boolean isSparse(){return numIds != null;}


    public Row(boolean sparse, int nNums, int nBins, int nresponses, int i, long start) {
      binIds = MemoryManager.malloc4(nBins);
      numVals = MemoryManager.malloc8d(nNums);
      response = MemoryManager.malloc8d(nresponses);
      if(sparse)
        numIds = MemoryManager.malloc4(nNums);
      this.nNums = sparse?0:nNums;
      cid = i;
      rid = start + i;
    }

    public Row(boolean sparse, double[] numVals, int[] binIds, double[] response, int i, long start) {
      int nNums = numVals == null ? 0:numVals.length;
      this.numVals = numVals;
      if(sparse)
        numIds = MemoryManager.malloc4(nNums);
      this.nNums = sparse ? 0:nNums;
      this.nBins = binIds == null ? 0:binIds.length;
      this.binIds = binIds;
      this.response = response;
      cid = i;
      rid = start + i;
    }

    public Row(double [] nums) {
      numVals = nums;
      nNums = nums.length;
    }
    public double response(int i) {return response[i];}

    public double get(int i) {
      int off = numStart();
      if(i >= off) { // numbers
        if(numIds == null)
          return numVals[i-off];
        int j = Arrays.binarySearch(numIds,0,nNums,i);
        return j >= 0?numVals[j]:0;
      } else { // categoricals
        int j = Arrays.binarySearch(binIds,0,nBins,i);
        return j >= 0?1:0;
      }
    }

    public void addNum(int id, double val) {
      if(numIds.length == nNums) {
        int newSz = Math.max(4,numIds.length + (numIds.length >> 1));
        numIds = Arrays.copyOf(numIds, newSz);
        numVals = Arrays.copyOf(numVals, newSz);
      }
      int i = nNums++;
      numIds[i] = id;
      numVals[i] = val;
    }

    public final double innerProduct(double [] vec) {
      double res = 0;
      int numStart = numStart();
      for(int i = 0; i < nBins; ++i)
        res += vec[binIds[i]];
      if(numIds == null || (vec.length == nBins + nNums + 1)) {
        for (int i = 0; i < numVals.length; ++i)
          res += numVals[i] * vec[numStart + i];
      } else {
        for (int i = 0; i < nNums; ++i)
          res += numVals[i] * vec[numIds[i]];
      }
      if(_intercept)
        res += vec[vec.length-1];
      return res;
    }

    public double[] expandCats() {
      if(isSparse() || _responses > 0) throw H2O.unimpl();

      int N = fullN();
      int numStart = numStart();
      double[] res = new double[N + (_intercept ? 1:0)];

      for(int i = 0; i < nBins; ++i)
        res[binIds[i]] = 1;
      if(numIds == null) {
        System.arraycopy(numVals,0,res,numStart,numVals.length);
      } else {
        for(int i = 0; i < nNums; ++i)
          res[numIds[i]] = numVals[i];
      }
      if(_intercept)
        res[res.length-1] = 1;
      return res;
    }

    public String toString() {
      return this.rid + Arrays.toString(Arrays.copyOf(binIds,nBins)) + ", " + Arrays.toString(numVals);
    }
    public void setResponse(int i, double z) {response[i] = z;}
  }

  /**
   * Get the offset into the expanded categorical
   * @param cid the column id
   * @param val the integer representation of the categorical level
   * @return offset into the fullN set of columns
   */
  public final int getCategoricalId(int cid, int val) {
    boolean isIWV = isInteractionVec(cid);
    if( !_useAllFactorLevels && !isIWV )  // categorical interaction vecs drop reference level in a special way
      val -= 1;
    if(val >= fullCatOffsets()[cid+1]) {  // previously unseen level
      assert _valid:"categorical value out of bounds, got " + val + ", next cat starts at " + fullCatOffsets()[cid+1];
      val = _catModes[cid] - (_useAllFactorLevels||isInteractionVec(cid)?0:1);
    }
    if (_catLvls[cid] != null) {  // some levels are ignored?
      assert _useAllFactorLevels;
      val = Arrays.binarySearch(_catLvls[cid], val);
    }
    return val < 0?-1:val + _catOffsets[cid];
  }

  public final int getCategoricalIdFromInteraction(int cid, int val) {
    InteractionWrappedVec v;
    if( (v=(InteractionWrappedVec)_adaptedFrame.vec(cid)).isCategorical() ) return getCategoricalId(cid,val);
    assert v.domains()!=null : "No domain levels found for interactions! cid: " + cid + " val: " + val;
    if( val >= _numOffsets[cid+1] ) { // previously unseen interaction (aka new domain level)
      assert _valid:"interaction value out of bounds, got " + val + ", next cat starts at " + _numOffsets[cid+1];
      val = v.mode();
    }
    return val < 0?-1:val+_numOffsets[cid];
  }

  public final Row extractDenseRow(double [] vals, Row row) {
    row.bad = false;
    row.rid = 0;
    row.cid = 0;
    if(row.weight == 0) return row;

    if (_skipMissing)
      for (double d:vals)
        if(Double.isNaN(d)) {
          row.bad = true;
          return row;
        }
    int nbins = 0;
    for (int i = 0; i < _cats; ++i) {
      int c = getCategoricalId(i,Double.isNaN(vals[i])?_catModes[i]:(int)vals[i]);
      if(c >= 0)row.binIds[nbins++] = c;
    }
    row.nBins = nbins;
    final int n = _nums;
    for (int i = 0; i < n; ++i) {
      if( isInteractionVec(i) ) {
        int offset;
        InteractionWrappedVec iwv = ((InteractionWrappedVec)_adaptedFrame.vec(_cats+i));
        int v1 = _adaptedFrame.find(iwv.v1());
        int v2 = _adaptedFrame.find(iwv.v2());
        if ( v1 < _cats ) offset = getCategoricalId(v1,Double.isNaN(vals[v1])?_catModes[v1]:(int)vals[v1]);
        else if (v2 < _cats) offset = getCategoricalId(v2,Double.isNaN(vals[v2])?_catModes[v1]:(int)vals[v2]);
        else offset = 0;
        row.numVals[i + offset] = vals[_cats + i]; // essentially: vals[v1] * vals[v2])
      }
      double d = vals[_cats + i]; // can be NA if skipMissing() == false
      if (Double.isNaN(d)) d = _numMeans[i];
      if (_normMul != null && _normSub != null)
        d = (d - _normSub[i]) * _normMul[i];
      row.numVals[i] = d;
    }
    int off = responseChunkId(0);
    for (int i = off; i < Math.min(vals.length,off + _responses); ++i) {
      try {
        row.response[i] = vals[responseChunkId(i)];
      } catch(Throwable t){
        throw new RuntimeException(t);
      }
      if (_normRespMul != null)
        row.response[i] = (row.response[i] - _normRespSub[i]) * _normRespMul[i];
      if (Double.isNaN(row.response[i])) {
        row.bad = true;
        return row;
      }
    }
    return row;
  }
  public final Row extractDenseRow(Chunk[] chunks, int rid, Row row) {
    row.bad = false;
    row.rid = rid + chunks[0].start();
    row.cid = rid;
    if(_weights)
      row.weight = chunks[weightChunkId()].atd(rid);
    if(row.weight == 0) return row;
    if (_skipMissing) {
      int N = _cats + _nums;
      for (int i = 0; i < N; ++i)
        if (chunks[i].isNA(rid)) {
          row.bad = true;
          return row;
        }
    }
    int nbins = 0;
    for (int i = 0; i < _cats; ++i) {
      int cid = getCategoricalId(i,chunks[i].isNA(rid)?_catModes[i]:(int)chunks[i].at8(rid));
      if(cid >= 0)
        row.binIds[nbins++] = cid;
    }
    row.nBins = nbins;
    final int n = _nums;
    for (int i = 0; i < n; ++i) {
      if( isInteractionVec(i) ) {
        int offset;
        InteractionWrappedVec iwv = ((InteractionWrappedVec)_adaptedFrame.vec(_cats+i));
        int v1 = _adaptedFrame.find(iwv.v1());
        int v2 = _adaptedFrame.find(iwv.v2());
        if( v1 < _cats )       offset = (int)chunks[v1].at8(rid);
        else if( v2 < _cats )  offset = (int)chunks[v2].at8(rid);
        else offset=0;
        row.numVals[i+offset] = chunks[_cats+i].atd(rid);  // essentially: chunks[v1].atd(rid) * chunks[v2].atd(rid) (see InteractionWrappedVec)
      } else {
        double d = chunks[_cats + i].atd(rid); // can be NA if skipMissing() == false
        if (Double.isNaN(d))
          d = _numMeans[i];
        if (_normMul != null && _normSub != null)
          d = (d - _normSub[i]) * _normMul[i];
        row.numVals[i] = d;
      }
    }
    for (int i = 0; i < _responses; ++i) {
      try {
        row.response[i] = chunks[responseChunkId(i)].atd(rid);
      } catch(Throwable t){
        throw new RuntimeException(t);
      }
      if (_normRespMul != null)
        row.response[i] = (row.response[i] - _normRespSub[i]) * _normRespMul[i];
      if (Double.isNaN(row.response[i])) {
        row.bad = true;
        return row;
      }
    }
    if(_offset)
      row.offset = chunks[offsetChunkId()].atd(rid);
    return row;
  }
  public Vec getWeightsVec(){return _adaptedFrame.vec(weightChunkId());}
  public Vec getOffsetVec(){return _adaptedFrame.vec(offsetChunkId());}
  public Row newDenseRow(){return new Row(false,numNums(),_cats,_responses,0,0);}  // TODO: _nums => numNums since currently extracting out interactions into dense
  public Row newDenseRow(double[] numVals, long start) {
    return new Row(false, numVals, null, null, 0, start);
  }

  public final class Rows {
    public final int _nrows;
    private final Row _denseRow;
    private final Row [] _sparseRows;
    public final boolean _sparse;
    private final Chunk [] _chks;

    private Rows(Chunk [] chks, boolean sparse) {
      _nrows = chks[0]._len;
      _sparse = sparse;
      long start = chks[0].start();
      if(sparse) {
        _denseRow = null;
        _chks = null;
        _sparseRows = extractSparseRows(chks);
      } else {
        _denseRow = DataInfo.this.newDenseRow();
        _chks = chks;
        _sparseRows = null;
      }
    }
    public Row row(int i) {return _sparse?_sparseRows[i]:extractDenseRow(_chks,i,_denseRow);}
  }

  public Rows rows(Chunk [] chks) {
    int cnt = 0;
    for(Chunk c:chks)
      if(c.isSparseZero())
        ++cnt;
    return rows(chks,cnt > (chks.length >> 1));
  }
  public Rows rows(Chunk [] chks, boolean sparse) {return new Rows(chks,sparse);}

  /**
   * Extract (sparse) rows from given chunks.
   * Note: 0 remains 0 - _normSub of DataInfo isn't used (mean shift during standarization is not reverted) - UNLESS offset is specified (for GLM only)
   * Essentially turns the dataset 90 degrees.
   * @param chunks - chunk of dataset
   * @return array of sparse rows
   */
  public final Row[] extractSparseRows(Chunk [] chunks) {
    if( _interactions!=null ) throw H2O.unimpl("sparse interactions");
    Row[] rows = new Row[chunks[0]._len];
    long startOff = chunks[0].start();
    for (int i = 0; i < rows.length; ++i) {
      rows[i] = new Row(true, Math.min(_nums, 16), _cats, _responses, i, startOff);
      rows[i].rid = chunks[0].start() + i;
      if(_offset)  {
        rows[i].offset = chunks[offsetChunkId()].atd(i);
        if(Double.isNaN(rows[i].offset)) rows[i].bad = true;
      }
      if(_weights) {
        rows[i].weight = chunks[weightChunkId()].atd(i);
        if(Double.isNaN(rows[i].weight)) rows[i].bad = true;
      }
    }
    // categoricals
    for (int i = 0; i < _cats; ++i) {
      for (int r = 0; r < chunks[0]._len; ++r) {
        Row row = rows[r];
        if(row.bad)continue;
        int cid = getCategoricalId(i,chunks[i].isNA(r)?_catModes[i]:(int)chunks[i].at8(r));
        if(cid >=0)
          row.binIds[row.nBins++] = cid;
      }
    }
    int numStart = numStart();
    // generic numbers
    for (int cid = 0; cid < _nums; ++cid) {
      Chunk c = chunks[_cats + cid];
      int oldRow = -1;
      for (int r = c.nextNZ(-1); r < c._len; r = c.nextNZ(r)) {
        if(c.atd(r) == 0)continue;
        assert r > oldRow;
        oldRow = r;
        Row row = rows[r];
        if (row.bad) continue;
        if (c.isNA(r)) row.bad = _skipMissing;
        double d = c.atd(r);
        if(Double.isNaN(d))
          d = _numMeans[cid];
        if(_normMul != null)
          d *= _normMul[cid];
        row.addNum(cid + numStart, d);
      }
    }
    // response(s)
    for (int i = 1; i <= _responses; ++i) {
      int rid = responseChunkId(i-1);
      Chunk rChunk = chunks[rid];
      for (int r = 0; r < chunks[0]._len; ++r) {
        Row row = rows[r];
        if(row.bad) continue;
        row.response[i-1] = rChunk.atd(r);
        if (_normRespMul != null) {
          row.response[i-1] = (row.response[i-1] - _normRespSub[i-1]) * _normRespMul[i-1];
        }
        if (Double.isNaN(row.response[row.response.length - i]))
          row.bad = true;
      }
    }
    return rows;
  }

}
