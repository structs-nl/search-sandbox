package nl.cleancode;

import java.io.IOException;
import java.nio.file.Paths;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.core.JsonProcessingException;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.Document;

import org.apache.lucene.facet.FacetsConfig;
import org.apache.lucene.facet.taxonomy.TaxonomyReader;
import org.apache.lucene.facet.DrillDownQuery;
import org.apache.lucene.facet.DrillSideways;

import org.apache.lucene.facet.taxonomy.directory.DirectoryTaxonomyWriter;
import org.apache.lucene.facet.taxonomy.directory.DirectoryTaxonomyReader;
import org.apache.lucene.index.IndexOptions;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.IndexWriterConfig.OpenMode;

import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;

import org.apache.lucene.index.Term;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.search.SearcherManager;
import org.apache.lucene.search.SearcherFactory;
import org.apache.lucene.facet.taxonomy.SearcherTaxonomyManager;
import org.apache.lucene.facet.taxonomy.SearcherTaxonomyManager.SearcherAndTaxonomy;


import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Date;
import java.util.HashSet;

import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;

import com.fasterxml.jackson.core.JsonPointer;
import org.apache.lucene.document.DateTools;
import org.apache.lucene.document.DoublePoint;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.LongPoint;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.document.Field.Store;
import org.apache.lucene.facet.FacetField;


import java.nio.file.FileVisitor;
import java.nio.file.FileVisitResult;
import java.nio.file.attribute.BasicFileAttributes;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.Path;

class IndexerPointerStore {

    protected Directory dir;
    protected Directory taxdir;
    protected Path filespath;

    private IndexWriterConfig iwc;
    private IndexWriter iw;
    private DirectoryTaxonomyWriter dtw;
    private FacetsConfig fconfig;

    private LinkedList<DateTimeFormatter> formatters;

    public SearcherTaxonomyManager searcherManager;

    jPointerStore pointerstore;

    public static final org.apache.lucene.document.FieldType TextFieldType = new org.apache.lucene.document.FieldType();


    static {
        TextFieldType.setIndexOptions(IndexOptions.DOCS_AND_FREQS_AND_POSITIONS_AND_OFFSETS);
        TextFieldType.setTokenized(true);
        TextFieldType.setStoreTermVectors(true);
        TextFieldType.setStoreTermVectorOffsets(true);
        TextFieldType.setStoreTermVectorPositions(true);
        TextFieldType.freeze();
      }

    IndexerPointerStore(jPointerStore jpointerstore, String basepath) throws IOException {
        pointerstore = jpointerstore;
        fconfig = new FacetsConfig();
        filespath = Paths.get(basepath + "/files");
        dir = FSDirectory.open(Paths.get(basepath + "/index/"));
        taxdir = FSDirectory.open(Paths.get(basepath + "/tax/"));
    }

    public void openReader()
    throws IOException, InterruptedException
    {
        //ir = DirectoryReader.open(dir);
        //taxoReader = new DirectoryTaxonomyReader(taxdir);
        //indexSearcherManager = new SearcherManager(iw, new SearcherFactory());

        if (iw == null || dtw == null)
            openWriter();

        searcherManager = new SearcherTaxonomyManager(iw, new SearcherFactory(), dtw);        
    }

    public void openWriter()
    throws IOException, InterruptedException
    {
        Analyzer analyzer = new StandardAnalyzer();
        iwc = new IndexWriterConfig(analyzer);
        iwc.setOpenMode(OpenMode.CREATE_OR_APPEND);
        iwc.setRAMBufferSizeMB(256.0);

        iw = new IndexWriter(dir, iwc);
        dtw = new DirectoryTaxonomyWriter(taxdir);
    }


    public void index(JsonNode json)
    throws IOException, JsonProcessingException, InterruptedException
    {
	        
        formatters = new LinkedList<DateTimeFormatter>();
        formatters.add(DateTimeFormatter.ISO_DATE_TIME);
        DateTimeFormatter localIso = DateTimeFormatter.ISO_LOCAL_DATE_TIME.withZone(ZoneId.systemDefault());
        formatters.add(localIso);	

	Iterator<JsonNode> iterator = json.elements();
	
	while (iterator.hasNext()) {

            JsonNode doc = iterator.next();

	    JsonNode uuid = json.at("/uuid");
	    JsonNode title = json.at("/title");
	    JsonNode parents = json.at("/parents");

	    Document luceneDoc = new Document();

	    luceneDoc.add(new StringField("uuid",uuid.asText(), Field.Store.YES));
	    
	    // fconfig.setHierarchical(fieldname, true);
	    
	    //luceneDoc.add(new StringField(fieldname, str, Field.Store.NO));
	    //luceneDoc.add(new FacetField(fieldname, str));

	    updateDoc(luceneDoc, new Term("Uuid", uuid.asText()) );
	    
	}
      	    
	    // String datetext = node.asText();
	    //ZonedDateTime zonedDateTime = tryPatterns(datetext, formatters);
	    //String encodedDateTime = DateTools.dateToString(Date.from(zonedDateTime.toInstant()),DateTools.Resolution.MILLISECOND);

	    //luceneDoc.add(new StringField(fieldname, encodedDateTime, Field.Store.NO));

	    //String y = Integer.toString(zonedDateTime.getYear());
	    //String m = Integer.toString(zonedDateTime.getMonthValue());
	    //String d = Integer.toString(zonedDateTime.getDayOfMonth());
	    //luceneDoc.add(new FacetField(fieldname, y, m, d));
	    
	    //luceneDoc.add(new StringField(fieldname, str, Field.Store.NO));
	    //luceneDoc.add(new FacetField(fieldname, str));	    
	    //luceneDoc.add(new DoublePoint(fieldname, node.asDouble()));
	    
            //updateDoc(luceneDoc, new Term("Uuid", uuid.asText()) );
    }

    public DrillDownQuery getDrillDownQuery(Query query)
    {
         return new DrillDownQuery(fconfig, query);
    }

    public DrillSideways getDrillSideways(SearcherAndTaxonomy st)
    throws IOException
    {
        return new DrillSideways(st.searcher, fconfig, st.taxonomyReader);
    }

    public SearcherAndTaxonomy getIndexSearcher()
    throws IOException, InterruptedException
    {
        if (searcherManager == null)
            openReader();

        return searcherManager.acquire();
    }

    public void releaseIndexSearcher(SearcherAndTaxonomy searcher)
    {
        try {
            searcherManager.release(searcher);
        } catch(Exception e) {
            System.out.println(e.toString());
        }
    }

    public Boolean refreshIndexSearcher()
    throws IOException, InterruptedException
    {
        if (searcherManager == null)
            openReader();

        return searcherManager.maybeRefresh();
    }

    public void commit()
    throws IOException
    {
        if (iw != null && dtw != null) {
            dtw.commit();
            iw.commit();
        }
    }

    public void updateDoc(Document doc, Term idterm)
    throws IOException, InterruptedException
    {
        if (iw == null)
            openWriter();

        iw.updateDocument(idterm, fconfig.build(dtw, doc));
    }

    public void deleteDoc(Term idterm)
    throws IOException, InterruptedException
    {       
        if (iw == null)
            openWriter();
        
        iw.deleteDocuments(idterm);
    }
    public void close() throws IOException {
        if (dtw != null)
            dtw.close();

        if (iw != null)
            iw.close();
    }

    public enum FieldType {
        STRING, DATETIME, TEXT, LONG, DOUBLE
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
