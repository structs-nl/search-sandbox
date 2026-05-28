package nl.structs;

import java.io.IOException;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import main.java.nl.structs.PayloadTokenLengthFilter;

import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.analysis.standard.StandardTokenizer;
import org.apache.lucene.analysis.core.LowerCaseFilter;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.document.Document;

import org.apache.lucene.facet.FacetsConfig;
import org.apache.lucene.facet.FacetsConfig.DrillDownTermsIndexing;

import org.apache.lucene.facet.taxonomy.directory.DirectoryTaxonomyWriter;
import org.apache.lucene.index.IndexOptions;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.IndexWriterConfig.OpenMode;

import org.apache.lucene.index.Term;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;

import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;

import org.apache.lucene.document.Field;
import org.apache.lucene.document.FieldType;

import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.facet.FacetField;

class Indexer {

  public Directory dir;
  public Directory taxdir;
  public FacetsConfig fconfig;

  private IndexWriterConfig iwc;
  private IndexWriter iw;
  private DirectoryTaxonomyWriter dtw;
  private Analyzer analyzer;

  private LinkedList<DateTimeFormatter> formatters;
  private ObjectMapper objectMapper;

  public static final FieldType TextFieldType = new FieldType();

  // The config and data should be separated
  // The data gathering should be external and is already done in another script

  static {
    TextFieldType.setIndexOptions(IndexOptions.DOCS_AND_FREQS_AND_POSITIONS_AND_OFFSETS);
    TextFieldType.setTokenized(true);
    TextFieldType.setStored(true);
    TextFieldType.setStoreTermVectors(true);
    TextFieldType.setStoreTermVectorOffsets(true);
    TextFieldType.setStoreTermVectorPositions(true);
    TextFieldType.setStoreTermVectorPayloads(true);
    TextFieldType.freeze();
  };

  Indexer(FSDirectory dir, FSDirectory taxdir) throws IOException {

    fconfig = new FacetsConfig();

    // TODO Part of the dimension config

    fconfig.setHierarchical("parents", true);
    fconfig.setMultiValued("parents", true);
    fconfig.setDrillDownTermsIndexing("parents", DrillDownTermsIndexing.ALL_PATHS_NO_DIM);
    fconfig.setRequireDimCount("parents", true);

    // analyzer = new StandardAnalyzer();
    analyzer = new CustomAnalyzer();

    iwc = new IndexWriterConfig(analyzer);
    iwc.setOpenMode(OpenMode.CREATE_OR_APPEND);
    iwc.setRAMBufferSizeMB(256.0);

    iw = new IndexWriter(dir, iwc);
    dtw = new DirectoryTaxonomyWriter(taxdir);

    objectMapper = new ObjectMapper();

  }

  class CustomAnalyzer extends Analyzer {

    // Same as StandardAnalyzer, only with the SynonymFilter and the
    // PayloadTokenFilter added

    @Override
    protected TokenStreamComponents createComponents(String fieldName) {

      final StandardTokenizer src = new StandardTokenizer();
      src.setMaxTokenLength(255);
      TokenStream tok = new LowerCaseFilter(src);

      // TODO annotations

      // This filter encodes the length of the token in the payload
      tok = new PayloadTokenLengthFilter(tok);

      return new TokenStreamComponents(
          r -> {
            src.setMaxTokenLength(255);
            src.setReader(r);
          }, tok);
    }

    @Override
    protected TokenStream normalize(String fieldName, TokenStream in) {
      return new LowerCaseFilter(in);
    }
  }

  public void indexDocument(JsonNode doc)
      throws IOException, JsonProcessingException, InterruptedException {

    // formatters = new LinkedList<DateTimeFormatter>();
    // formatters.add(DateTimeFormatter.ISO_DATE_TIME);
    // DateTimeFormatter localIso =
    // DateTimeFormatter.ISO_LOCAL_DATE_TIME.withZone(ZoneId.systemDefault());
    // formatters.add(localIso);

    /*
     * process a single document
     * go through the fields per record
     * 
     * "type": "string", "text, "facet", "date"
     * "store": true/false
     * "identifier": true/false (defaults to false)
     * 
     * in the config:
     * text options:
     * facet options: multivalued, hierarchical, drilldown, dimcount
     * 
     */

    var luceneDoc = new Document();
    String identifyingField = "";
    String identifyingValue = "";

    var fieldIterator = doc.iterator();

    while (fieldIterator.hasNext()) {
      var field = fieldIterator.next();

      var nameNode = field.at("/name");
      if (nameNode.isMissingNode() || nameNode.isEmpty()) {
        // TODO error

      }
      var fieldName = nameNode.asText();
      var fieldType = field.at("/type").asText("string"); // TODO test with missingNode
      var fieldStore = field.at("/store").asBoolean(false); // TODO test with missingNode
      var fieldIdentifer = field.at("/identifier").asBoolean(false); // TODO test with missingNode

      var valueNode = field.at("/value");

      // if (text != null && !text.isNull() && text.asText() != null &&
      // !text.asText().isEmpty())
      // luceneDoc.add(new Field("content", text.asText(), TextFieldType));

      if (fieldType.equals("string")) {

        // TODO check that valueNode is a text

        var store = Field.Store.NO;
        if (fieldStore)
          store = Field.Store.YES;

        // TODO: shouldn't it be the same field?

        if (fieldIdentifer) {
          identifyingField = fieldName;
          identifyingValue = valueNode.asText();
        }

        luceneDoc.add(new StringField(fieldName, valueNode.asText(), store));
      }

      if (fieldType.equals("facet")) {

        // TODO this can be a value, a list of paths or a list of values

        if (valueNode.isArray()) {
          var valIt = valueNode.iterator();
          while (valIt.hasNext()) {
            var valueElem = valIt.next();

            // TODO this can be a list of paths or a list of values
            if (valueElem.isArray()) {
              var parpath = new ArrayList<String>();
              var pathIt = valueElem.iterator();
              while (pathIt.hasNext()) {
                var pathElem = pathIt.next();
                parpath.add(pathElem.asText());
              }

              if (!parpath.isEmpty())
                luceneDoc.add(new FacetField(fieldName, parpath.toArray(new String[0])));
            }
          }
        }
      }
    }

    // TODO: check that identifying field and value are set

    iw.updateDocument(new Term(identifyingField, identifyingValue), fconfig.build(dtw, luceneDoc));

    dtw.commit();
    iw.commit();

    // String datetext = node.asText();
    // ZonedDateTime zonedDateTime = tryPatterns(datetext, formatters);
    // String encodedDateTime =
    // DateTools.dateToString(Date.from(zonedDateTime.toInstant()),DateTools.Resolution.MILLISECOND);

    // luceneDoc.add(new StringField(fieldname, encodedDateTime, Field.Store.NO));

    // String y = Integer.toString(zonedDateTime.getYear());
    // String m = Integer.toString(zonedDateTime.getMonthValue());
    // String d = Integer.toString(zonedDateTime.getDayOfMonth());
    // luceneDoc.add(new FacetField(fieldname, y, m, d));

  }

  public void close() throws IOException {
    if (dtw != null)
      dtw.close();

    if (iw != null)
      iw.close();
  }

  public static ZonedDateTime tryPatterns(String date, List<DateTimeFormatter> formatters) {
    for (DateTimeFormatter formatter : formatters) {
      try {
        ZonedDateTime zonedDateTime = ZonedDateTime.parse(date, formatter);
        return zonedDateTime;
      } catch (Exception e) {

      }
    }
    throw new IllegalArgumentException("Could not parse " + date);
  }
}