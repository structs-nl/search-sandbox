package nl.structs;

import java.io.IOException;

// TODO: check namespace

import main.java.nl.structs.AnnotateFilter.Annotation;
import main.java.nl.structs.AnnotatedField;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.netty.buffer.ByteBuf;

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
import org.apache.lucene.util.IOUtils;

import java.time.LocalDate;
import java.time.Period;
import java.time.temporal.ChronoField;
import java.time.temporal.TemporalField;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.zip.GZIPInputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.charset.UnsupportedCharsetException;

import org.apache.lucene.document.Field;
import org.apache.lucene.document.Field.Store;
import org.apache.lucene.document.FieldType;

import org.apache.lucene.document.StringField;
import org.apache.lucene.facet.FacetField;
import org.apache.lucene.document.TextField;

class Indexer {

  protected ObjectMapper mapper;
  public Directory dir;
  public Directory taxdir;
  public FacetsConfig fconfig;

  private IndexWriterConfig iwc;
  private IndexWriter iw;
  private DirectoryTaxonomyWriter dtw;
  private Analyzer analyzer;

  Indexer(FSDirectory dir, FSDirectory taxdir, ObjectMapper mapper) throws IOException {

    this.mapper = mapper;
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

  public void indexURL(String url)
      throws IOException, URISyntaxException, JsonProcessingException, InterruptedException, UnsupportedCharsetException {

      var uri = new URI(url);
      var conn = uri.toURL().openConnection();
      var inputstream = conn.getInputStream();

      if (conn.getContentEncoding() == "gzip") {
        inputstream = new GZIPInputStream(inputstream);
      }
    
      var json = mapper.readTree(inputstream);
      indexDocument(json);
  }

  public void indexDocument(JsonNode doc)
      throws IOException, JsonProcessingException, InterruptedException {

    var luceneDoc = new Document();

    String identifyingField = "";
    String identifyingValue = "";

    if (! doc.isArray()) {
      throw new IllegalArgumentException(pointer("/", "document payload must be a JSON array of fields"));
    }

    var fieldIndex = 0;
    for (var field : doc){
      var fieldPointer = pointer("/" + fieldIndex);

      var nameNode = field.at("/name");
      if (nameNode.isMissingNode() || nameNode.isEmpty() || !nameNode.isTextual()) {
        throw new IllegalArgumentException(pointer(fieldPointer + "/name", "each field must contain a textual name"));
      }
      
      var fieldName = nameNode.asText();

      var valueNode = field.at("/value");
      if (valueNode.isMissingNode() || valueNode.isEmpty()) {
        throw new IllegalArgumentException(pointer(fieldPointer + "/value", "field '" + fieldName + "' is missing a value"));
      }

      var typeNode = field.at("/type");
      if (typeNode.isMissingNode() || typeNode.isEmpty() || !typeNode.isTextual()) {
        throw new IllegalArgumentException(pointer(fieldPointer + "/type", "each field must contain a textual type"));
      }

      var storeNode = field.at("/store");
      if (storeNode.isMissingNode() || storeNode.isEmpty() || !storeNode.isBoolean()) {
        throw new IllegalArgumentException(pointer(fieldPointer + "/store", "each field must contain a boolean store flag"));
      }

      var identifierNode = field.at("/identifier");
      //if (identifierNode.isMissingNode() || identifierNode.isEmpty() || !identifierNode.isBoolean()) {
      //  throw new IllegalArgumentException(pointer(fieldPointer + "/identifier", "each field must contain a boolean identifier flag"));
      //}

      var fieldType = typeNode.asText();
      var fieldStore = storeNode.asBoolean();
      var fieldIdentifer = identifierNode.asBoolean();

      if (fieldType.equals("annotatedtext")) {

        if (!valueNode.isTextual()) {
          throw new IllegalArgumentException(pointer(fieldPointer + "/value", "annotated text field '" + fieldName + "' must be textual"));
        }

        var fieldAnn = field.at("/annotations");

        if (fieldAnn.isMissingNode() || !fieldAnn.isArray()) {
          throw new IllegalArgumentException(pointer(fieldPointer + "/annotations", "annotated text field '" + fieldName + "' must contain an annotations array"));
        }

        var annotations = new LinkedList<Annotation>();
        var annotationIndex = 0;

        for (var ann : fieldAnn) {
            var annotationPointer = pointer(fieldPointer + "/annotations/" + annotationIndex);
            var tagNode = ann.at("/tag");
            var fromNode = ann.at("/from");
            var toNode = ann.at("/to");

            if (tagNode.isMissingNode() || !tagNode.isTextual()) {
              throw new IllegalArgumentException(pointer(annotationPointer + "/tag", "annotation tag must be textual"));
            }

            if (fromNode.isMissingNode() || !fromNode.canConvertToInt()) {
              throw new IllegalArgumentException(pointer(annotationPointer + "/from", "annotation 'from' must be an integer"));
            }

            if (toNode.isMissingNode() || !toNode.canConvertToInt()) {
              throw new IllegalArgumentException(pointer(annotationPointer + "/to", "annotation 'to' must be an integer"));
            }

            var tag = tagNode.asText();
            var from = fromNode.asInt();
            var to = toNode.asInt();
            annotations.add(new Annotation(from, to, tag));
            annotationIndex++;
        }
        
        // We assume they are all within the char range of the text and ordered

        luceneDoc.add(new AnnotatedField(fieldName, valueNode.asText(), annotations));

      } else if (fieldType.equals("daterange")) {

        if (!valueNode.isObject()) {
          throw new IllegalArgumentException(pointer(fieldPointer + "/value", "date range field '" + fieldName + "' must be an object"));
        }

        var fromNode = valueNode.at("/from");
        var toNode = valueNode.at("/to");

        if (!fromNode.isTextual() || !toNode.isTextual()) {
          throw new IllegalArgumentException(pointer(fieldPointer + "/value", "date range field '" + fieldName + "' must contain textual 'from' and 'to' values"));
        }

        var fromStr = fromNode.textValue();
        var fromDate = LocalDate.parse(fromStr);
        var fromEpochDay = fromDate.toEpochDay();

        var toStr = toNode.textValue();
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
          throw new IllegalArgumentException(pointer(fieldPointer + "/value", "string field '" + fieldName + "' must be textual"));
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

        // This is always a list of lists
        // Otherwise, we cannot distinguish a path from a list of values

        if (valueNode.isArray()) {
          for (var valueElem : valueNode) {
            if (valueElem.isArray()) {
              var parpath = new ArrayList<String>();
              for (var pathElem : valueElem){
                parpath.add(pathElem.asText());
              }

              if (!parpath.isEmpty())
                luceneDoc.add(new FacetField(fieldName, parpath.toArray(new String[0])));
            }
          }
        } else {
          throw new IllegalArgumentException(pointer(fieldPointer + "/value", "facet field '" + fieldName + "' must be an array"));
        }
      } else {
        throw new IllegalArgumentException(pointer(fieldPointer + "/type", "unsupported field type '" + fieldType + "' for field '" + fieldName + "'"));
      }

      fieldIndex++;
    }

    if (identifyingField.isEmpty() || identifyingValue.isEmpty()) {
      throw new IllegalArgumentException(pointer("/", "document payload must contain exactly one identifying field"));
    }

    iw.updateDocument(new Term(identifyingField, identifyingValue), fconfig.build(dtw, luceneDoc));

    dtw.commit();
    iw.commit();
  }

  public void close() throws IOException {
    IOUtils.close(dtw, iw);
  }

  private String pointer(String jsonPointer) {
    return jsonPointer;
  }

  private String pointer(String jsonPointer, String message) {
    return jsonPointer + ": " + message;
  }
}