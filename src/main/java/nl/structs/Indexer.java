package nl.structs;

import java.io.IOException;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.core.JsonProcessingException;

import main.java.nl.structs.PayloadTokenLengthFilter;

import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.analysis.standard.StandardTokenizer;
import org.apache.lucene.analysis.core.LowerCaseFilter;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.document.Document;

import org.apache.lucene.facet.range.LongRange;
import org.apache.lucene.search.LongValuesSource;
import org.apache.lucene.facet.range.LongRangeFacetCounts;
import org.apache.lucene.document.LongRangeDocValuesField;
import org.apache.lucene.facet.facetset.LongFacetSet;

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

import java.time.LocalDate;
import java.time.Period;

import java.util.ArrayList;

import org.apache.lucene.document.Field;
import org.apache.lucene.document.FieldType;

import org.apache.lucene.document.StringField;
import org.apache.lucene.facet.FacetField;

class Indexer {

  public Directory dir;
  public Directory taxdir;
  public FacetsConfig fconfig;

  private IndexWriterConfig iwc;
  private IndexWriter iw;
  private DirectoryTaxonomyWriter dtw;
  private Analyzer analyzer;

  public static final FieldType TextFieldType = new FieldType();

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

    var luceneDoc = new Document();

    String identifyingField = "";
    String identifyingValue = "";

    if (! doc.isArray()) {
      // TODO error
    }

    for (var field : doc){

      var nameNode = field.at("/name");
      if (nameNode.isMissingNode() || nameNode.isEmpty() || !nameNode.isTextual()) {
        // TODO error

      }
      
      var fieldName = nameNode.asText();

      var valueNode = field.at("/value");
      if (valueNode.isMissingNode() || valueNode.isEmpty()) {
        // TODO error

      }

      var fieldType = field.at("/type").asText("string");             // TODO test with missingNode
      var fieldStore = field.at("/store").asBoolean(false);           // TODO test with missingNode
      var fieldIdentifer = field.at("/identifier").asBoolean(false);  // TODO test with missingNode

      if (fieldType.equals("text")) {

        if (!valueNode.isTextual()) {
          // Raise error

        }
        
        luceneDoc.add(new Field(fieldName, valueNode.asText(), TextFieldType));

      } else if (fieldType.equals("daterange")) {

        var fromStr = valueNode.at("/from").textValue();
        var fromDate = LocalDate.parse(fromStr);
        var fromEpochDay = fromDate.toEpochDay();

        var toStr = valueNode.at("/to").textValue();
        var toDate = LocalDate.parse(toStr);
        var toEpochDay = toDate.toEpochDay();
      
        // century quarter bucket specific; TODO: generalize:
        // x period per century if years > 0
        // x period per year if years = 0

        var bucketPeriod = Period.parse("P25Y");

        // The start year of the century of the from date
        var fromYearCentury = (fromDate.getYear() / 100) * 100;
        var fromCenturyStart = LocalDate.of(fromYearCentury, 1, 1);
        var fromYearCentQuarter = ((fromDate.getYear() - fromYearCentury ) / 25);

        // The start year of the century of the to date
        var toYearCentury = (toDate.getYear() / 100) * 100;
        var toCenturyStart = LocalDate.of(toYearCentury, 1, 1);
        var toYearCentQuarter = ((toDate.getYear() - toYearCentury ) / 25);

        var bucketFirstStartDate = fromCenturyStart.plus(bucketPeriod.multipliedBy(fromYearCentQuarter));
        var bucketLastStartDate = toCenturyStart.plus(bucketPeriod.multipliedBy(toYearCentQuarter));

        // General from here
        var periods = new ArrayList<LocalDate>();
        var current = bucketFirstStartDate;

        while (current.isBefore(bucketLastStartDate) || current.isEqual(bucketLastStartDate)) {
          periods.add(current);
          current = current.plus(bucketPeriod);
        }
        
        // 1607-01-01 - 1796-12-31

      } else if (fieldType.equals("string")) {

        if (!valueNode.isTextual()) {
          // Raise error

        }

        var store = Field.Store.NO;
        if (fieldStore)
          store = Field.Store.YES;

        // TODO: shouldn't it be the same field?

        if (fieldIdentifer) {
          identifyingField = fieldName;
          identifyingValue = valueNode.asText();
        }

        luceneDoc.add(new StringField(fieldName, valueNode.asText(), store));

      } else if (fieldType.equals("facet")) {

        // TODO this can be a value, a list of paths or a list of values

        if (valueNode.isArray()) {
          for (var valueElem : valueNode) {
            // TODO this can be a list of paths or a list of values
            if (valueElem.isArray()) {
              
              var parpath = new ArrayList<String>();
              for (var pathElem : valueElem){
                parpath.add(pathElem.asText());
              }

              if (!parpath.isEmpty())
                luceneDoc.add(new FacetField(fieldName, parpath.toArray(new String[0])));
            }
          }
        }
      } else {
        // TODO: error:

      }
    }

    // TODO: check that identifying field and value are set

    iw.updateDocument(new Term(identifyingField, identifyingValue), fconfig.build(dtw, luceneDoc));

    dtw.commit();
    iw.commit();
  }

  public void close() throws IOException {
    if (dtw != null)
      dtw.close();

    if (iw != null)
      iw.close();
  }
}