package nl.structs;

import java.io.IOException;

// TODO: check namespace
import main.java.nl.structs.AnnotateFilter.Annotation;
import main.java.nl.structs.AnnotatedField;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.core.JsonProcessingException;

import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.document.Document;

import org.apache.lucene.facet.range.LongRange;
import org.apache.lucene.search.LongValuesSource;
import org.apache.lucene.facet.range.LongRangeFacetCounts;
import org.apache.lucene.document.LongRangeDocValuesField;
import org.apache.lucene.facet.facetset.LongFacetSet;

import org.apache.lucene.facet.FacetsConfig;
import org.apache.lucene.facet.FacetsConfig.DrillDownTermsIndexing;
import org.apache.lucene.facet.taxonomy.directory.DirectoryTaxonomyWriter;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.IndexWriterConfig.OpenMode;

import org.apache.lucene.index.Term;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;

import java.time.LocalDate;
import java.time.Period;

import java.util.ArrayList;
import java.util.LinkedList;


import org.apache.lucene.document.Field;
import org.apache.lucene.document.Field.Store;
import org.apache.lucene.document.FieldType;

import org.apache.lucene.document.StringField;
import org.apache.lucene.facet.FacetField;
import org.apache.lucene.document.TextField;

class Indexer {

  public Directory dir;
  public Directory taxdir;
  public FacetsConfig fconfig;

  private IndexWriterConfig iwc;
  private IndexWriter iw;
  private DirectoryTaxonomyWriter dtw;
  private Analyzer analyzer;

  Indexer(FSDirectory dir, FSDirectory taxdir) throws IOException {

    fconfig = new FacetsConfig();

    // TODO Part of the dimension config

    fconfig.setHierarchical("parents", true);
    fconfig.setMultiValued("parents", true);
    fconfig.setDrillDownTermsIndexing("parents", DrillDownTermsIndexing.ALL_PATHS_NO_DIM);
    fconfig.setRequireDimCount("parents", true);

    analyzer = new StandardAnalyzer();

    iwc = new IndexWriterConfig(analyzer);
    iwc.setOpenMode(OpenMode.CREATE_OR_APPEND);
    iwc.setRAMBufferSizeMB(256.0);

    iw = new IndexWriter(dir, iwc);
    dtw = new DirectoryTaxonomyWriter(taxdir);

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

      if (fieldType.equals("annotatedtext")) {

        if (!valueNode.isTextual()) {
          // Raise error

        }

        var annotations = new LinkedList<Annotation>();

        // TODO: go through the annotations in the ingest json and add them
        // We assume they are all within the char range of the text and ordered

        luceneDoc.add(new AnnotatedField(fieldName, valueNode.asText(), annotations));

      } else if (fieldType.equals("daterange")) {

        var fromStr = valueNode.at("/from").textValue();
        var fromDate = LocalDate.parse(fromStr);
        var fromEpochDay = fromDate.toEpochDay();

        var toStr = valueNode.at("/to").textValue();
        var toDate = LocalDate.parse(toStr);
        var toEpochDay = toDate.toEpochDay();
      
        // century quarter bucket specific
        // x period per century if years > 0
        // x period per year if years = 0

        // TODO: move this to the indexing script

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

        luceneDoc.add(new StringField(fieldName, valueNode.asText(), Store.YES));

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