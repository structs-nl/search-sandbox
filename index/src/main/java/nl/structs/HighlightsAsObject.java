package nl.structs;

import java.io.IOException;
import java.util.Map;

import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.uhighlight.UnifiedHighlighter;

public class HighlightsAsObject extends UnifiedHighlighter {

  public HighlightsAsObject(UnifiedHighlighter.Builder builder) {
    super(builder);
  }

  // Expose the protected method publicly
  // This is a general class, used to get the result of a custom highlighter out
  public UnifiedHighlighter.OffsetSource offsetSource(String field) {
    return super.getOffsetSource(field);
  }

  // Expose the protected method publicly
  public Map<String, Object[]> highlight(
      String[] fields,
      Query query,
      ScoreDoc[] scoreDocs,
      int[] maxPassages) throws IOException {

    // Extract document IDs from ScoreDoc array
    int[] docIds = new int[scoreDocs.length];
    for (int i = 0; i < scoreDocs.length; i++) {
      docIds[i] = scoreDocs[i].doc;
    }

    return this.highlightFieldsAsObjects(fields, query, docIds, maxPassages);
  }
}
