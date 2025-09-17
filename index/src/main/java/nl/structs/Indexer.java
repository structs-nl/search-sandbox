package nl.structs;

import java.io.IOException;
import java.nio.file.Paths;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.core.JsonProcessingException;

import org.apache.lucene.analysis.standard.StandardAnalyzer;
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

import java.util.Iterator;
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

    private LinkedList<DateTimeFormatter> formatters;
    
    Searcher Searcher;
    
    public static final FieldType TextFieldType = new FieldType();
    
    static {
        TextFieldType.setIndexOptions(IndexOptions.DOCS_AND_FREQS_AND_POSITIONS_AND_OFFSETS);
        TextFieldType.setTokenized(true);
        TextFieldType.setStoreTermVectors(true);
        TextFieldType.setStoreTermVectorOffsets(true);
        TextFieldType.setStoreTermVectorPositions(true);
        TextFieldType.freeze();
    }
    
    Indexer(String basepath) throws IOException {
	
        fconfig = new FacetsConfig();
	
	fconfig.setHierarchical("parents", true);
	fconfig.setMultiValued("parents", true);
	fconfig.setDrillDownTermsIndexing("parents", DrillDownTermsIndexing.ALL_PATHS_NO_DIM);
	fconfig.setRequireDimCount("parents", true);
	
        dir = FSDirectory.open(Paths.get(basepath + "/index/"));
        taxdir = FSDirectory.open(Paths.get(basepath + "/tax/"));
	
        var analyzer = new StandardAnalyzer();
        iwc = new IndexWriterConfig(analyzer);
        iwc.setOpenMode(OpenMode.CREATE_OR_APPEND);
        iwc.setRAMBufferSizeMB(256.0);
	
        iw = new IndexWriter(dir, iwc);
        dtw = new DirectoryTaxonomyWriter(taxdir);
	
    }
    
    public void index(JsonNode json)
	throws IOException, JsonProcessingException, InterruptedException
    {
	
        //formatters = new LinkedList<DateTimeFormatter>();
        //formatters.add(DateTimeFormatter.ISO_DATE_TIME);
        //DateTimeFormatter localIso = DateTimeFormatter.ISO_LOCAL_DATE_TIME.withZone(ZoneId.systemDefault());
        //formatters.add(localIso);	
	
	Iterator<JsonNode> iterator = json.elements();
	
	while (iterator.hasNext()) {
	    
            JsonNode doc = iterator.next();
	    
	    JsonNode uuid = doc.at("/uuid");
	    JsonNode title = doc.at("/title");
	    JsonNode type = doc.at("/type");
	    JsonNode parents = doc.at("/parents");
	    
	    
	    Document luceneDoc = new Document();
	    
	    luceneDoc.add(new StringField("uuid",uuid.asText(), Field.Store.YES));
	    luceneDoc.add(new TextField("type", type.asText(), Field.Store.YES));
	    
	    luceneDoc.add(new TextField("title", title.asText(), Field.Store.YES));
	    
	    var pariter = parents.elements();
	    var parpath = new LinkedList<String>();
	    
	    while (pariter.hasNext()) {
		var parent = pariter.next();
		parpath.add(parent.asText());
	    }
	    
	    if (! parpath.isEmpty()){
		var path = new String[parpath.size()];
		path = parpath.toArray(path);		
		luceneDoc.add(new FacetField("parents",path));
	    }
	    
	    iw.updateDocument(new Term("uuid", uuid.asText()),  fconfig.build(dtw, luceneDoc));
	}
	
	dtw.commit();
	iw.commit();
	
	// String datetext = node.asText();
	//ZonedDateTime zonedDateTime = tryPatterns(datetext, formatters);
	//String encodedDateTime = DateTools.dateToString(Date.from(zonedDateTime.toInstant()),DateTools.Resolution.MILLISECOND);
	
	//luceneDoc.add(new StringField(fieldname, encodedDateTime, Field.Store.NO));

	//String y = Integer.toString(zonedDateTime.getYear());
	//String m = Integer.toString(zonedDateTime.getMonthValue());
	//String d = Integer.toString(zonedDateTime.getDayOfMonth());
	//luceneDoc.add(new FacetField(fieldname, y, m, d));
	
    }

    public void close() throws IOException {
        if (dtw != null)
            dtw.close();
	
        if (iw != null)
            iw.close();
    }
    
    public static ZonedDateTime tryPatterns(String date, List<DateTimeFormatter> formatters){
        for(DateTimeFormatter formatter: formatters){
            try {
                ZonedDateTime zonedDateTime = ZonedDateTime.parse(date, formatter);
                return zonedDateTime;
            } catch(Exception e){
		
            }
        }
        throw new IllegalArgumentException("Could not parse " + date);
    }
}
