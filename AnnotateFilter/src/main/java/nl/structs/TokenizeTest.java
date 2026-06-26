package main.java.nl.structs;

import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.analysis.synonym.SynonymMap;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.analysis.tokenattributes.OffsetAttribute;
import org.apache.lucene.analysis.tokenattributes.PositionIncrementAttribute;
import org.apache.lucene.analysis.tokenattributes.PositionLengthAttribute;

import org.apache.lucene.tests.analysis.TokenStreamToDot;
import org.apache.lucene.util.CharsRef;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.File;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedList;


import org.apache.lucene.analysis.synonym.SynonymGraphFilter;
import org.apache.lucene.analysis.synonym.SynonymMap;
import org.apache.lucene.util.CharsRef;


public class TokenizeTest {
  TokenizeTest() {
    var analyzer = new StandardAnalyzer();

    try {

      /*

      var synonymBuilder = new SynonymMap.Builder(true);
      synonymBuilder.add(new CharsRef("snaphanen"), new CharsRef("concept00001"), true);
      synonymBuilder.add(new CharsRef("axel\u0000anthonij\u0000rosenquest"), new CharsRef("person00001"), true);

      var synonymMap = synonymBuilder.build();


      tok = new SynonymGraphFilter(tok, synonymMap, true);

       */

            /* 

      var tokenStream = analyzer.tokenStream("field", "This is a 'test of the tokenization process");  
      var annotations = new LinkedList<AnnotateFilter.Annotation>();
      
      annotations.add(new AnnotateFilter.Annotation(5, 9, "concept1"));
      annotations.add(new AnnotateFilter.Annotation(10, 14, "concept3"));
      annotations.add(new AnnotateFilter.Annotation(10, 42, "concept2"));
      annotations.add(new AnnotateFilter.Annotation(18, 21, "concept32"));
      annotations.add(new AnnotateFilter.Annotation(18, 34, "concept5"));
      annotations.add(new AnnotateFilter.Annotation(35, 42, "concept8"));
    
      tokenStream = new AnnotateFilter(tokenStream, annotations);
      outputDot(tokenStream);

      */

      var mapper = new ObjectMapper();

      System.out.println("Loading annotations");

      var tags = mapper.readTree(new File("./testdata/3598/entity-tags.json"));
      var annotations = new LinkedList<AnnotateFilter.Annotation>();
      
      for (var tag : tags) {
        var start = tag.get("start_in_doc").asInt();
        var end = tag.get("end_in_doc").asInt();
        var concept = tag.get("tag").asText();
        annotations.add(new AnnotateFilter.Annotation(start, end, concept));
      }

      System.out.println("Loaded " + annotations.size() + " annotations");

      System.out.println("Reading file");
      var text = Files.readString(Paths.get("./testdata/3598/document.txt"));

      System.out.println("Tokenizing, annotating and outputting");
      var tokenStream = analyzer.tokenStream("field", text);
      tokenStream = new AnnotateFilter(tokenStream, annotations);

      printTokenStream(tokenStream);
      
      //outputDot(tokenStream);
      System.out.println("Done");


    } catch (Exception e) {
      e.printStackTrace();
    } finally {
      analyzer.close();
    }
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

    var writer = new PrintWriter("tokens.txt");

    var offsetAttribute = tokenStream.getAttribute(OffsetAttribute.class);
    var positionIncrementAttribute = tokenStream.getAttribute(PositionIncrementAttribute.class);
    var positionLengthAttribute = tokenStream.getAttribute(PositionLengthAttribute.class);
    var termAttribute = tokenStream.getAttribute(CharTermAttribute.class);

    tokenStream.reset();

    while (tokenStream.incrementToken()) {
      var string =  offsetAttribute.startOffset() + "\t" + 
                    offsetAttribute.endOffset() + "\t" +
                    termAttribute.toString() + "\t" +
                    positionIncrementAttribute.getPositionIncrement() + "\t" +
                    positionLengthAttribute.getPositionLength();
      writer.println(string);
    }
    writer.close();
    tokenStream.end();
    tokenStream.close();
  }
}