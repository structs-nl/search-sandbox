package nl.structs;

import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.synonym.SynonymGraphFilter;
import org.apache.lucene.analysis.TokenFilter;
import org.apache.lucene.analysis.core.FlattenGraphFilter;
import org.apache.lucene.analysis.synonym.SynonymMap;
import org.apache.lucene.util.CharsRef;

import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.analysis.tokenattributes.OffsetAttribute;
import org.apache.lucene.analysis.tokenattributes.PositionIncrementAttribute;
import org.apache.lucene.analysis.tokenattributes.PositionLengthAttribute;

import org.apache.lucene.tests.analysis.TokenStreamToDot;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;

public class TokenizeTest {
    TokenizeTest() {
        Analyzer analyzer = new StandardAnalyzer();
        String text = "This is a test of the tokenization process";
        try {
            var tokenStream = analyzer.tokenStream("field", text);
            var builder = new SynonymMap.Builder(true);

            // Multi-token synonym values must be encoded as a single CharsRef
            // where individual tokens are joined with the reserved separator (\u0000).


            // leijff eijgen concept:2e3b9397-4638-4cd0-b0f1-3670b82b2186
            // Paduwackang concept:e2133a89-248c-4e3a-85dd-11ab62ae181f

            //builder.add(new CharsRef("test"), new CharsRef("exam\u0000in\u0000ation"), true); // one original, three synonym tokens
            //builder.add(new CharsRef("of\u0000the\u0000tokenization\u0000process"), new CharsRef("section3"), true); // three original, one synonym token
            //builder.add(new CharsRef("this\u0000is\u0000a"), new CharsRef("section1"), true); // three original, one synonym token
            builder.add(new CharsRef("a"), new CharsRef("section2"), true); // three original, one synonym token
            builder.add(new CharsRef("is"), new CharsRef("section1"), true); // three original, one synonym token

            builder.add(new CharsRef("leijff eijgen"), new CharsRef("concept:2e3b9397-4638-4cd0-b0f1-3670b82b2186"), true); // three original, one synonym token

            builder.add(new CharsRef("a\u0000test"), new CharsRef("section3"), true); // three original, one synonym token
            
            var synonymMap = builder.build();

            tokenStream = new SynonymGraphFilter(tokenStream, synonymMap, true);

            var annotations = new LinkedList<Annotation>();
            annotations.add(new Annotation(15, 20, "concept"));

            tokenStream = new TestFilter(tokenStream, annotations);

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

    public class Annotation {
        int startOffset;
        int endOffset;
        String annotation;

        public Annotation(int startOffset, int endOffset, String annotation) {
            this.startOffset = startOffset;
            this.endOffset = endOffset;
            this.annotation = annotation;
        }
    }

    class TestFilter extends TokenFilter {

        private OffsetAttribute offsetAttribute;
        private PositionIncrementAttribute positionIncrementAttribute;
        private PositionLengthAttribute positionLengthAttribute;
        private CharTermAttribute termAttribute;

        private List<Annotation> annolist;

        protected TestFilter(TokenStream input, List<Annotation> annotations) {
            super(input);

            // TODO: SortedSet or NavigableSet seem more appropriate for the annotation collection
            // TODO: sub token annotations can use the correct offsets in the original text

            annolist = annotations;

            offsetAttribute = input.getAttribute(OffsetAttribute.class);
            positionIncrementAttribute = input.getAttribute(PositionIncrementAttribute.class);
            positionLengthAttribute = input.getAttribute(PositionLengthAttribute.class);
            termAttribute = input.getAttribute(CharTermAttribute.class);
        }

        @Override
        public boolean incrementToken() throws java.io.IOException {

            if (input.incrementToken()){

                var startOffset = offsetAttribute.startOffset();
                var endOffset = offsetAttribute.endOffset();

                // find the annotations that start at the current token (lookup)

                // determine the end: lookahead: input / output buffers

                System.out.println("AnnotationFilter:"
                    + " offset: " + offsetAttribute.startOffset() + "-" + offsetAttribute.endOffset()
                    + "\tpos incr: " + positionIncrementAttribute.getPositionIncrement()
                    + "\tpos len: " + positionLengthAttribute.getPositionLength()
                    + "\tterm: " + termAttribute.toString() 
            
                );
                return true;
            } else {
                return false;
            }
        }
        
    }
}