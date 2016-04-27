package hex.schemas;

import hex.coxph.CoxPHModel;
import water.Key;
import water.api.ModelOutputSchema;
import water.api.ModelSchema;

public class CoxPHModelV3 extends ModelSchema<CoxPHModel,
                                              CoxPHModelV3,
                                              CoxPHModel.CoxPHParameters,
                                              CoxPHV3.CoxPHParametersV3,
                                              CoxPHModel.CoxPHOutput,
                                              CoxPHModelV3.CoxPHModelOutputV3> {

  public static final class CoxPHModelOutputV3 extends ModelOutputSchema<CoxPHModel.CoxPHOutput, CoxPHModelOutputV3> {
    //FIXME
    //add output fields

  } // CoxPHModelOutputV2

  // TOOD: I think we can implement the following two in ModelSchema, using reflection on the type parameters.
  public CoxPHV3.CoxPHParametersV3 createParametersSchema() { return new CoxPHV3.CoxPHParametersV3(); }
  public CoxPHModelOutputV3 createOutputSchema() { return new CoxPHModelOutputV3(); }

  //==========================
  // Custom adapters go here

  // Version&Schema-specific filling into the impl
  @Override public CoxPHModel createImpl() {
    CoxPHModel.CoxPHParameters parms = parameters.createImpl();
    return new CoxPHModel(Key.make() /*dest*/, parms, new CoxPHModel.CoxPHOutput(null));
  }
}
