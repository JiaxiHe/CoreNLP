package edu.stanford.nlp.neural;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.function.Function;

import org.ejml.simple.SimpleMatrix;

import edu.stanford.nlp.neural.SimpleTensor;
import edu.stanford.nlp.parser.dvparser.DVModel;
import edu.stanford.nlp.parser.dvparser.DVModelReranker;
import edu.stanford.nlp.parser.dvparser.DVParser;
import edu.stanford.nlp.parser.lexparser.LexicalizedParser;
import edu.stanford.nlp.sentiment.RNNOptions;
import edu.stanford.nlp.sentiment.SentimentModel;
import edu.stanford.nlp.util.ErasureUtils;
import edu.stanford.nlp.util.Generics;
import edu.stanford.nlp.util.StringUtils;
import edu.stanford.nlp.util.TwoDimensionalMap;

public class ConvertModels {

  public enum Stage {
    OLD, NEW
  }

  public enum Model {
    SENTIMENT, DVPARSER
  }

  /**
   * Transform this map into a new map using the given function
   */
  public static <K1, K2, V, V2> TwoDimensionalMap<K1, K2, V2> transform2DMap(TwoDimensionalMap<K1, K2, V> in, 
                                                                             Function<V, V2> function) {
    // TODO: reuse the map factories (it needs a cast)
    // however we currently are using this for Sentiment, which we know needs a TreeMap
    TwoDimensionalMap<K1, K2, V2> out = TwoDimensionalMap.treeMap();
    out.addAll(in, function);
    return out;
  }

  public static List<List<Double>> fromMatrix(SimpleMatrix in) {
    List<List<Double>> out = new ArrayList<>();

    for (int i = 0; i < in.numRows(); ++i) {
      out.add(new ArrayList<>());
      for (int j = 0; j < in.numCols(); ++j) {
        out.get(i).add(in.get(i, j));
      }
    }

    return out;
  }

  public static List<List<List<Double>>> fromTensor(SimpleTensor in) {
    List<List<List<Double>>> out = new ArrayList<>();

    for (int i = 0; i < in.numSlices(); ++i) {
      out.add(fromMatrix(in.getSlice(i)));
    }

    return out;
  }

  public static SimpleMatrix toMatrix(List<List<Double>> in) {
    if (in.size() == 0) {
      throw new IllegalArgumentException("Input array with 0 rows");
    }
    if (in.get(0).size() == 0) {
      throw new IllegalArgumentException("Input array with 0 columns");
    }
    for (int i = 1; i < in.size(); ++i) {
      if (in.get(i).size() != in.get(0).size()) {
        throw new IllegalArgumentException("Input array with uneven columns");
      }
    }

    SimpleMatrix out = new SimpleMatrix(in.size(), in.get(0).size());
    for (int i = 0; i < in.size(); ++i) {
      List<Double> row = in.get(i);
      for (int j = 0; j < row.size(); ++j) {
        out.set(i, j, row.get(j));
      }
    }

    return out;
  }

  public static SimpleTensor toTensor(List<List<List<Double>>> in) {
    int numSlices = in.size();
    SimpleMatrix[] slices = new SimpleMatrix[numSlices];
    for (int i = 0; i < numSlices; ++i) {
      slices[i] = toMatrix(in.get(i));
    }
    return new SimpleTensor(slices);
  }

  public static <K, V, V2> Map<K, V2> transformMap(Map<K, V> in, Function<V, V2> function) {
    Map<K, V2> transformed = Generics.newTreeMap();
    for (K k : in.keySet()) {
      transformed.put(k, function.apply(in.get(k)));
    }
    return transformed;
  }


  public static void writeSentiment(SentimentModel model, ObjectOutputStream out)
    throws IOException
  {
    Function<SimpleMatrix, List<List<Double>>> f = (SimpleMatrix x) -> fromMatrix(x);

    out.writeObject(transform2DMap(model.binaryTransform, f));
    out.writeObject(transform2DMap(model.binaryTensors, (SimpleTensor x) -> fromTensor(x)));
    out.writeObject(transform2DMap(model.binaryClassification, f));

    out.writeObject(transformMap(model.unaryClassification, f));
    out.writeObject(transformMap(model.wordVectors, f));

    out.writeObject(model.op);
  }

  public static SentimentModel readSentiment(ObjectInputStream in)
    throws IOException, ClassNotFoundException
  {
    Function<List<List<Double>>, SimpleMatrix> f = (List<List<Double>> x) -> toMatrix(x);

    TwoDimensionalMap<String, String, List<List<Double>>> map2dSM = 
      ErasureUtils.uncheckedCast(in.readObject());
    TwoDimensionalMap<String, String, SimpleMatrix> binaryTransform = transform2DMap(map2dSM, f);

    TwoDimensionalMap<String, String, List<List<List<Double>>>> map2dST = 
      ErasureUtils.uncheckedCast(in.readObject());
    TwoDimensionalMap<String, String, SimpleTensor> binaryTensor = 
      transform2DMap(map2dST, (x) -> toTensor(x));

    map2dSM = ErasureUtils.uncheckedCast(in.readObject());
    TwoDimensionalMap<String, String, SimpleMatrix> binaryClassification = transform2DMap(map2dSM, f);

    Map<String, List<List<Double>>> map = ErasureUtils.uncheckedCast(in.readObject());
    Map<String, SimpleMatrix> unaryClassification = transformMap(map, f);

    map = ErasureUtils.uncheckedCast(in.readObject());
    Map<String, SimpleMatrix> wordVectors = transformMap(map, f);

    RNNOptions op = ErasureUtils.uncheckedCast(in.readObject());

    return new SentimentModel(binaryTransform, binaryTensor, binaryClassification,
                              unaryClassification, wordVectors, op);
  }

  public static void writeParser(LexicalizedParser model, DVModelReranker reranker, ObjectOutputStream out)
    throws IOException
  {
    out.writeObject(model);
    
    Function<SimpleMatrix, List<List<Double>>> f = (SimpleMatrix x) -> fromMatrix(x);

    DVModel dvmodel = reranker.getModel();
    out.writeObject(transform2DMap(dvmodel.binaryTransform, f));
    out.writeObject(transformMap(dvmodel.unaryTransform, f));
    out.writeObject(transform2DMap(dvmodel.binaryScore, f));
    out.writeObject(transformMap(dvmodel.unaryScore, f));
    out.writeObject(transformMap(dvmodel.wordVectors, f));
  }

  public static LexicalizedParser readParser(ObjectInputStream in) 
    throws IOException, ClassNotFoundException
  {
    LexicalizedParser model = ErasureUtils.uncheckedCast(in.readObject());

    Function<List<List<Double>>, SimpleMatrix> f = (x) -> toMatrix(x);

    TwoDimensionalMap<String, String, List<List<Double>>> map2dSM = 
      ErasureUtils.uncheckedCast(in.readObject());
    TwoDimensionalMap<String, String, SimpleMatrix> binaryTransform = transform2DMap(map2dSM, f);

    Map<String, List<List<Double>>> map = ErasureUtils.uncheckedCast(in.readObject());
    Map<String, SimpleMatrix> unaryTransform = transformMap(map, f);

    map2dSM = ErasureUtils.uncheckedCast(in.readObject());
    TwoDimensionalMap<String, String, SimpleMatrix> binaryScore = transform2DMap(map2dSM, f);

    map = ErasureUtils.uncheckedCast(in.readObject());
    Map<String, SimpleMatrix> unaryScore = transformMap(map, f);

    map = ErasureUtils.uncheckedCast(in.readObject());
    Map<String, SimpleMatrix> wordVectors = transformMap(map, f);

    DVModel dvModel = new DVModel(binaryTransform, unaryTransform, binaryScore,
                                  unaryScore, wordVectors, model.getOp());
    DVModelReranker reranker = new DVModelReranker(dvModel);
    model.reranker = reranker;
    return model;
  }

  public static void main(String[] args) throws IOException, ClassNotFoundException {
    Properties props = StringUtils.argsToProperties(args);

    final Stage stage;
    try {
      stage = Stage.valueOf(props.getProperty("stage").toUpperCase());
    } catch (IllegalArgumentException | NullPointerException e) {
      throw new IllegalArgumentException("Please specify -stage, either OLD or NEW");
    }

    final Model modelType;
    try {
      modelType = Model.valueOf(props.getProperty("model").toUpperCase());
    } catch (IllegalArgumentException | NullPointerException e) {
      throw new IllegalArgumentException("Please specify -model, either SENTIMENT or DVPARSER");
    }

    if (!props.containsKey("input")) {
      throw new IllegalArgumentException("Please specify -input");
    }

    if (!props.containsKey("output")) {
      throw new IllegalArgumentException("Please specify -output");
    }

    if (modelType == Model.SENTIMENT) {
      if (stage == Stage.OLD) {
        SentimentModel model = SentimentModel.loadSerialized(props.getProperty("input"));
        ObjectOutputStream out = new ObjectOutputStream(new FileOutputStream(props.getProperty("output")));
        writeSentiment(model, out);
        out.close();
      } else {
        ObjectInputStream in = new ObjectInputStream(new FileInputStream(props.getProperty("input")));
        SentimentModel model = readSentiment(in);
        model.saveSerialized(props.getProperty("output"));
      }
    } else if (modelType == Model.DVPARSER) {
      if (stage == Stage.OLD) {
        String inFile = props.getProperty("input");
        LexicalizedParser model = LexicalizedParser.loadModel(inFile);
        if (model.reranker == null) {
          System.out.println("Nothing to do for " + inFile);
        } else {
          DVModelReranker reranker = (DVModelReranker) model.reranker; // will barf if not successful
          model.reranker = null;
          ObjectOutputStream out = new ObjectOutputStream(new FileOutputStream(props.getProperty("output")));
          writeParser(model, reranker, out);
          out.close();
        }
      } else {
        ObjectInputStream in = new ObjectInputStream(new FileInputStream(props.getProperty("input")));
        LexicalizedParser model = readParser(in);
        model.saveParserToSerialized(props.getProperty("output"));
      }
    }
  }
}

