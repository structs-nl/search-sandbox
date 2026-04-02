package nl.structs;

import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Paths;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.URI;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.analysis.standard.StandardTokenizer;
import org.apache.lucene.analysis.core.LowerCaseFilter;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.document.Document;

import org.apache.lucene.analysis.synonym.SynonymGraphFilter;
import org.apache.lucene.analysis.synonym.SynonymMap;
import org.apache.lucene.util.CharsRef;

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
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;

import org.apache.lucene.document.Field;
import org.apache.lucene.document.FieldType;

import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.facet.FacetField;

import org.apache.lucene.tests.analysis.TokenStreamToDot;
import java.io.PrintWriter;

class Indexer {

    public Directory dir;
    public Directory taxdir;
    public FacetsConfig fconfig;

    private IndexWriterConfig iwc;
    private IndexWriter iw;
    private DirectoryTaxonomyWriter dtw;
    private Analyzer analyzer;

    private LinkedList<DateTimeFormatter> formatters;
    private HttpClient httpClient;
    private ObjectMapper objectMapper;

    private SynonymMap synonymMap;
    private SynonymMap.Builder synonymBuilder;

    public static final FieldType TextFieldType = new FieldType();

    // The indexer is custom code, iterating a file specified on the command line and doing some data operations, including lookups
    // The config and data should be separated. The data gathering should be external and is already done in another script
    // The config of the indexer should be part of the Querier class

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

        fconfig.setHierarchical("parents", true);
        fconfig.setMultiValued("parents", true);
        fconfig.setDrillDownTermsIndexing("parents", DrillDownTermsIndexing.ALL_PATHS_NO_DIM);
        fconfig.setRequireDimCount("parents", true);

        //analyzer = new StandardAnalyzer();
        analyzer = new CustomAnalyzer();

        iwc = new IndexWriterConfig(analyzer);
        iwc.setOpenMode(OpenMode.CREATE_OR_APPEND);
        iwc.setRAMBufferSizeMB(256.0);

        iw = new IndexWriter(dir, iwc);
        dtw = new DirectoryTaxonomyWriter(taxdir);

        httpClient = HttpClient.newHttpClient();
        objectMapper = new ObjectMapper();

        // synonym test

        synonymBuilder = new SynonymMap.Builder(true);
        synonymBuilder.add(new CharsRef("snaphanen"), new CharsRef("concept00001"), true);
        synonymBuilder.add(new CharsRef("axel\u0000anthonij\u0000rosenquest"), new CharsRef("person00001"), true);
        
        synonymMap = synonymBuilder.build();
    }

    class CustomAnalyzer extends Analyzer {

        // Same as StandardAnalyzer, only with the SynonymFilter and the PayloadTokenFilter added

        @Override
        protected TokenStreamComponents createComponents(String fieldName) {

            final StandardTokenizer src = new StandardTokenizer();
            src.setMaxTokenLength(255);
            TokenStream tok = new LowerCaseFilter(src);

            tok = new SynonymGraphFilter(tok, synonymMap, true);
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

    public void index(JsonNode json)
            throws IOException, JsonProcessingException, InterruptedException {

        // formatters = new LinkedList<DateTimeFormatter>();
        // formatters.add(DateTimeFormatter.ISO_DATE_TIME);
        // DateTimeFormatter localIso =
        // DateTimeFormatter.ISO_LOCAL_DATE_TIME.withZone(ZoneId.systemDefault());
        // formatters.add(localIso);

        Iterator<JsonNode> iterator = json.elements();

        /* 
            data in an array or a single record
            go through the fields per record
            the records need to have the identifier field
            
            data type values: String, Text, Integer, Facet
            store the field value?

            in the config:
            text options: 
            facet options: multivalued, hierarchical, drilldown, dimcount
                     
        */


        while (iterator.hasNext()) {

            JsonNode doc = iterator.next();

            JsonNode uuid = doc.at("/uuid");
            JsonNode title = doc.at("/title");
            JsonNode type = doc.at("/type");
            JsonNode texturl = doc.at("/texturl");

            System.out.println(texturl.asText());

            Document luceneDoc = new Document();

            luceneDoc.add(new StringField("uuid", uuid.asText(), Field.Store.YES));
            luceneDoc.add(new StringField("type", type.asText(), Field.Store.YES));
                        
            if (texturl != null && !texturl.isNull() && texturl.asText() != null && !texturl.asText().isEmpty()) {
                
                var fullText = resolveAndParseJson(texturl.asText());
                luceneDoc.add(new Field("content", fullText, TextFieldType));
            }

            luceneDoc.add(new Field("title", title.asText(), TextFieldType));


            JsonNode parents = doc.at("/parents");

            var parpath = new ArrayList<String>();
            var pariter = parents.elements();
            while (pariter.hasNext()) {
                parpath.add(pariter.next().asText());
            }
            if (!parpath.isEmpty())
                luceneDoc.add(new FacetField("parents", parpath.toArray(new String[0])));

            iw.updateDocument(new Term("uuid", uuid.asText()), fconfig.build(dtw, luceneDoc));
        }

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

    private String resolveAndParseJson(String urlString) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(urlString))
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {

                var text = "";
                var json = objectMapper.readTree(response.body());

                for (Iterator<JsonNode> it = json.elements(); it.hasNext();) {
                    var node = it.next();
                    if (node.isObject() && node.has("text")) {
                        text += "\n" + node.get("text").asText();
                    }
                }
                return text;

            } else {
                System.err.println("Failed to fetch URL " + urlString + ": HTTP " + response.statusCode());
                return null;
            }
        } catch (Exception e) {
            System.err.println("Error resolving URL " + urlString + ": " + e.getMessage());
            return null;
        }
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