package nl.structs;

import java.util.LinkedList;
import java.util.List;

import org.apache.lucene.search.uhighlight.Passage;
import org.apache.lucene.search.uhighlight.PassageFormatter;
import org.apache.lucene.util.BytesRef;

public class HighlightsFormatter extends PassageFormatter {

    // This is a formatter, getting the details of the hits out

    public class HighlightResult {
    public int start;
    public int end;
    public String term;
    public String text;
    public String prefix;
    public String suffix;

    public HighlightResult(int start, int end, String term, String text, String prefix, String suffix) {
        this.start = start;
        this.end = end;
        this.term = term;
        this.text = text;
        this.prefix = prefix;
        this.suffix = suffix;
    }
  }

  @Override
  public LinkedList<HighlightResult> format(Passage[] passages, String content) {
    var prefixlength = 30;
    var suffixlength = 30;

    var result = new LinkedList<HighlightResult>();

    for (var passage : passages) {
      for (int i = 0; i < passage.getNumMatches(); i++) {
        int start = passage.getMatchStarts()[i];
        int end = passage.getMatchEnds()[i];
        BytesRef term = passage.getMatchTerms()[i];

        result.add(new HighlightResult(start, end, term.utf8ToString(), content.substring(start, end),
            content.substring(start - prefixlength, start),
            content.substring(end, end + suffixlength)));
      }
    }

    return result;
  }
}
