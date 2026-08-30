package nl.structs;
import java.io.IOException;

// TODO: check namespace

import main.java.nl.structs.AnnotateFilter.Annotation;
import main.java.nl.structs.AnnotatedField;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.fasterxml.jackson.core.JsonProcessingException;

import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.document.Document;

import org.apache.lucene.facet.FacetsConfig;
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
import java.nio.file.Files;
import java.nio.file.Path;

import org.apache.lucene.document.Field;
import org.apache.lucene.document.Field.Store;
import org.apache.lucene.document.FieldType;

import org.apache.lucene.document.StringField;
import org.apache.lucene.document.IntField;
import org.apache.lucene.facet.FacetField;
import org.apache.lucene.document.TextField;

class Indexer {

  private ObjectMapper mapper;
  private FacetsConfig fconfig;

  private IndexWriterConfig iwc;
  private IndexWriter iw;
  private DirectoryTaxonomyWriter dtw;
  private Analyzer analyzer;

  Indexer(FSDirectory dir, FSDirectory taxdir, ObjectMapper mapper, FacetsConfig facetsConfig) throws IOException {

    this.mapper = mapper;
    this.fconfig = facetsConfig;

    analyzer = new StandardAnalyzer();

    iwc = new IndexWriterConfig(analyzer);
    iwc.setOpenMode(OpenMode.CREATE_OR_APPEND);
    iwc.setRAMBufferSizeMB(256.0);

    iw = new IndexWriter(dir, iwc);
    dtw = new DirectoryTaxonomyWriter(taxdir);

  }

  static void initializeEmptyIndexesIfNeeded(Path indexPath, Path taxPath) throws IOException {
    if (hasExistingContent(indexPath) || hasExistingContent(taxPath)) {
      return;
    }

    try (var indexDir = FSDirectory.open(indexPath);
         IndexWriter writer = new IndexWriter(indexDir, new IndexWriterConfig(new StandardAnalyzer()))) {
      writer.commit();
    }

    try (var taxDir = FSDirectory.open(taxPath);
         DirectoryTaxonomyWriter taxonomyWriter = new DirectoryTaxonomyWriter(taxDir)) {
      taxonomyWriter.commit();
    }
  }

  private static boolean hasExistingContent(Path path) throws IOException {
    if (!Files.exists(path)) {
      return false;
    }

    try (var stream = Files.list(path)) {
      return stream.findAny().isPresent();
    }
  }

  public void index(String input)
      throws IOException, URISyntaxException, JsonProcessingException, InterruptedException, UnsupportedCharsetException {

      var urls = input.split("\n");
      for (var url : urls) {
        var uri = new URI(url);
        indexURL(uri);
      }
  }
  public void indexURL(URI uri)
      throws IOException, JsonProcessingException, InterruptedException, UnsupportedCharsetException {

      var jsondoc = getJSON(uri);
      var indexeableDocs = indexDocuments(jsondoc, uri.toString() + "#");

      update(indexeableDocs);

      dtw.commit();
      iw.commit();
  }

  private JsonNode getJSON(URI uri)
        throws IOException, JsonProcessingException, InterruptedException, UnsupportedCharsetException {

      var conn = uri.toURL().openConnection();
      var inputstream = conn.getInputStream();
    
      if (conn.getContentEncoding().equals("gzip")) {
        inputstream = new GZIPInputStream(inputstream);
      }
      var json = mapper.readTree(inputstream);

      return json;
  }

  public ArrayList<IndexeableDocument> indexDocuments(JsonNode json, String pointerprefix)
      throws IOException, JsonProcessingException, InterruptedException {

      var indexeableDocs = new ArrayList<IndexeableDocument>();

      // The json can contain 
      // 1) a single document: a "fields" field with an array of fields
      // 2) a "documents" field with an array of objects with a "fields" field

      if (json.has("documents")) {
        // a documents object with an array of documents
        var documents = json.get("documents");

        var docIndex = 0;
        for (var doc : documents) {
          var docPointer = pointerprefix + "/documents/"  + docIndex;

          if (doc.has("fields")){
          var fields = doc.get("fields");
            if (fields.isArray()) {
              //System.out.println(docPointer);
            indexeableDocs.add(createDocument(fields, docPointer + "/fields"));
              
            } else {
              throw new IllegalArgumentException("the fields object must contain an array");
            }
          }
          docIndex++;
        }

      } else if (json.has("fields")){
        //  a single document with an array of fields

        var fields = json.get("fields");
        if (fields.isArray()) {
          
          indexeableDocs.add(createDocument(fields, pointerprefix + "/fields"));

        } else {
          throw new IllegalArgumentException("the fields object must contain an array");
        }
      } else {
        throw new IllegalArgumentException("the top level must be a documents or fields field");
      }

      return indexeableDocs;
  }

  private record IndexeableDocument( Document document, String identifyingFieldName, String identifyingValue, String path) {
  }

  private void update(ArrayList<IndexeableDocument> indexeableDocs) throws IOException {

    for (var doc : indexeableDocs) {
        try {
          iw.updateDocument(new Term(doc.identifyingFieldName, doc.identifyingValue), fconfig.build(dtw, doc.document));
        } catch (Exception e) {
          System.out.println(doc.path);
            throw e;
            // throw new IndexingException(pointerprefix, e);
        }
    }
  }

  private IndexeableDocument createDocument(JsonNode doc, String pointerprefix)
      throws IOException, JsonProcessingException, InterruptedException {

    var luceneDoc = new Document();

    // TODO check the parsing of a document and adjust the facet config
    // TODO: error when a facet is not in the config?

    String identifyingFieldName = "";
    String identifyingValue = "";

    if (! doc.isArray()) {
      throw new IllegalArgumentException("the document must be a JSON array of fields");
    }

    var fieldIndex = 0;
    for (var field : doc){
      var fieldPointer = pointerprefix + "/" + fieldIndex;

      var nameNode = field.at("/name");
      if (nameNode.isMissingNode() || !nameNode.isTextual()) {
        throw new IllegalArgumentException(fieldPointer + "/name" +  ": each field must contain a textual name");
      }
      
      var fieldName = nameNode.asText();

      var valueNode = field.at("/value");
      if (valueNode.isMissingNode()) {
        throw new IllegalArgumentException(fieldPointer + "/value" + ": field '" + fieldName + "' is missing a value");
      }

      var typeNode = field.at("/type");
      if (typeNode.isMissingNode() || !typeNode.isTextual()) {
        throw new IllegalArgumentException(fieldPointer + "/type: each field must contain a textual type");
      }

      var storeNode = field.at("/store");

      // TODO: default store option
      // Date ranges are currently not set in the files

      //if (storeNode.isMissingNode() || !storeNode.isBoolean()) {
      //  throw new IllegalArgumentException(fieldPointer + "/store: each field must contain a boolean store flag");
      //}

      //var identifierNode = field.at("/identifier");
      //if (identifierNode.isMissingNode() || identifierNode.isEmpty() || !identifierNode.isBoolean()) {
      //  throw new IllegalArgumentException(fieldPointer + "/identifier: each field must contain a boolean identifier flag");
      //}

      var fieldType = typeNode.asText();
      var fieldStore = storeNode.asBoolean();
      //var fieldIdentifer = identifierNode.asBoolean();

      if (fieldType.equals("string")) {

        if (!valueNode.isTextual()) {
          throw new IllegalArgumentException(fieldPointer + "/value: string field '" + fieldName + "' must be textual");
        }

        // TODO: default for all fields
        var store = Field.Store.YES;
        if (!fieldStore)
          store = Field.Store.NO;

        var stringfield = new StringField(fieldName, valueNode.asText(), Store.YES);

        // TODO: generalize: add an optional identifying bool property. and what about integers?

        if (fieldName.equals("identifier")) {
          identifyingFieldName = fieldName;
          identifyingValue = valueNode.asText();    
        }

        luceneDoc.add(stringfield);

      } else if (fieldType.equals("integer")) {

        if (!valueNode.isInt()) {
          throw new IllegalArgumentException(fieldPointer + "/value: integer field '" + fieldName + "' must be an integer");
        }

        luceneDoc.add(new IntField(fieldName, valueNode.asInt(), Store.YES));

      } else if (fieldType.equals("annotatedtext")) {

        if (!valueNode.isTextual()) {
          throw new IllegalArgumentException(fieldPointer + "/value: annotated text field '" + fieldName + "' must be textual");
        }

        var fieldAnn = field.at("/annotations");

        if (fieldAnn.isMissingNode() || !fieldAnn.isArray()) {
          throw new IllegalArgumentException(fieldPointer + "/annotations: annotated text field '" + fieldName + "' must contain an annotations array");
        }

        var annotations = new LinkedList<Annotation>();
        var annotationIndex = 0;

        for (var ann : fieldAnn) {
            var annotationPointer = fieldPointer + "/annotations/" + annotationIndex;
            var tagNode = ann.at("/tag");
            var fromNode = ann.at("/from");
            var toNode = ann.at("/to");

            if (tagNode.isMissingNode() || !tagNode.isTextual()) {
              throw new IllegalArgumentException(annotationPointer + "/tag: annotation tag must be textual");
            }

            if (fromNode.isMissingNode() || !fromNode.canConvertToInt()) {
              throw new IllegalArgumentException(annotationPointer + "/from: annotation 'from' must be an integer");
            }

            if (toNode.isMissingNode() || !toNode.canConvertToInt()) {
              throw new IllegalArgumentException(annotationPointer + "/to: annotation 'to' must be an integer");
            }

            var tag = tagNode.asText();
            // distinguish the tags from other terms and make them lower case. Otherwise, we cannot find them with the standard analyzer on the query
            tag = "tag_" + tag.toLowerCase().trim();
            var from = fromNode.asInt();
            var to = toNode.asInt();
            annotations.add(new Annotation(from, to, tag));
            annotationIndex++;
        }
        
        // We assume they are all within the char range of the text and ordered

        luceneDoc.add(new AnnotatedField(fieldName, valueNode.asText(), annotations));

      } else if (fieldType.equals("daterange")) {

        if (!valueNode.isObject()) {
          throw new IllegalArgumentException(fieldPointer + "/value: date range field '" + fieldName + "' must be an object");
        }

        var fromNode = valueNode.at("/from");
        var toNode = valueNode.at("/to");

        if (!fromNode.isTextual() || !toNode.isTextual()) {
          throw new IllegalArgumentException(fieldPointer + "/value: date range field '" + fieldName + "' must contain textual 'from' and 'to' values");
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
          throw new IllegalArgumentException(fieldPointer + "/value: facet field '" + fieldName + "' must be an array");
        }

      } else {
        throw new IllegalArgumentException(fieldPointer + "/type: unsupported field type '" + fieldType + "' for field '" + fieldName + "'");
      }

      fieldIndex++;
    }

    if (identifyingFieldName.isEmpty() || identifyingValue.isEmpty()) {
      throw new IllegalArgumentException(pointerprefix + ": document payload must contain an identifying field");
    }

    return new IndexeableDocument(luceneDoc, identifyingFieldName, identifyingValue, pointerprefix);

  }

  public class IndexingException extends RuntimeException { 
    public IndexingException(String errorMessage, Throwable err) {
      super(errorMessage, err);
    }
  }

  public void close() throws IOException {
    IOUtils.close(dtw, iw);
  }
}