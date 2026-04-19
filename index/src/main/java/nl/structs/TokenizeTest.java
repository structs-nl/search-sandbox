package nl.structs;

import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.synonym.SynonymGraphFilter;
import org.apache.lucene.analysis.synonym.SynonymMap;
import org.apache.lucene.util.CharsRef;

import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.analysis.tokenattributes.OffsetAttribute;
import org.apache.lucene.analysis.tokenattributes.PositionIncrementAttribute;
import org.apache.lucene.analysis.tokenattributes.PositionLengthAttribute;

import org.apache.lucene.tests.analysis.TokenStreamToDot;

import java.io.PrintWriter;
import java.util.LinkedList;

public class TokenizeTest {
  TokenizeTest() {
    Analyzer analyzer = new StandardAnalyzer();
    String text = "This is a test of the tokenization process";
    try {
      var tokenStream = analyzer.tokenStream("field", text);
      var builder = new SynonymMap.Builder(true);

      // builder.add(new CharsRef("test"), new CharsRef("exam\u0000in\u0000ation"),
      // true); // one original, three synonym tokens
      
      builder.add(new CharsRef("a"), new CharsRef("section2"), true); // three original, one synonym token
      builder.add(new CharsRef("is"), new CharsRef("section1"), true); // three original, one synonym token
      builder.add(new CharsRef("a\u0000test"), new CharsRef("section3"), true); // three original, one synonym token

      var synonymMap = builder.build();
      //tokenStream = new SynonymGraphFilter(tokenStream, synonymMap, true);

      var annotations = new LinkedList<AnnotateFilter.Annotation>();
      annotations.add(new AnnotateFilter.Annotation(1, 6, "concept1"));
      annotations.add(new AnnotateFilter.Annotation(8, 14, "concept"));

      annotations.add(new AnnotateFilter.Annotation(15, 23, "concept2"));
      //annotations.add(new AnnotateFilter.Annotation(35, 42, "concept3"));

      tokenStream = new AnnotateFilter(tokenStream, annotations);

      outputDot(tokenStream);

      //printTokenStream(tokenStream);

      System.out.println("done");

    } catch (Exception e) {
      e.printStackTrace();
    }
    analyzer.close();
  }

  public static void main(String[] args) {
    new TokenizeTest();
  }

  private static void outputDot(TokenStream tokenStream) throws Exception {
    var dotwriter = new PrintWriter("graph.dot");
    new TokenStreamToDot(null, tokenStream, dotwriter).toDot();
    dotwriter.close();
  }

  private static void printTokenStream(TokenStream tokenStream) throws Exception {
    var offsetAttribute = tokenStream.getAttribute(OffsetAttribute.class);
    var positionIncrementAttribute = tokenStream.getAttribute(PositionIncrementAttribute.class);
    var positionLengthAttribute = tokenStream.getAttribute(PositionLengthAttribute.class);
    var termAttribute = tokenStream.getAttribute(CharTermAttribute.class);

    tokenStream.reset();
    while (tokenStream.incrementToken()) {
      System.out.println(termAttribute.toString());
      System.out.println("position increment: " + positionIncrementAttribute.getPositionIncrement());
      System.out.println("position length: " + positionLengthAttribute.getPositionLength());
      System.out.println("offset: " + offsetAttribute.startOffset() + "-" + offsetAttribute.endOffset());
      System.out.println("");
    }
    tokenStream.end();
    tokenStream.close();
  }

}