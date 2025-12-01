package nl.structs;

import java.io.IOException;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.HashMap;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.uuid.Generators;

import io.netty.buffer.ByteBufOutputStream;
import io.netty.buffer.Unpooled;
import io.netty.buffer.ByteBuf;

import org.apache.lucene.facet.taxonomy.directory.DirectoryTaxonomyReader;
import org.apache.lucene.facet.DrillDownQuery;
import org.apache.lucene.facet.DrillSideways;
import org.apache.lucene.analysis.standard.StandardAnalyzer;

import org.apache.lucene.queryparser.classic.QueryParser;
import org.apache.lucene.util.IOUtils;

import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.Query;

import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.ScoreDoc;

import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.Term;

public class Querier {

    protected Searcher _searcher;
    protected HashMap<String, SearchState> searchstates = new HashMap<String, SearchState>();
    
    public Querier(Searcher searcher) {
	_searcher = searcher;
    }
    
    protected class SearchState {
	public Query query;
	public ScoreDoc scoredoc;
	
	SearchState(Query q, ScoreDoc sd) {
	    query = q;
	    scoredoc = sd;
	}
    }

    public static SearchQuery parseQuery(JsonNode json)
    {
	// Get the query out of the JSON
	
	var sq = new SearchQuery();
	var qidnode = json.at("/qid");

	if (!qidnode.isMissingNode() && !qidnode.isNull() && !qidnode.asText().isEmpty()) {
	    sq.queryid = qidnode.asText();
	}

	var pagenode = json.at("/pagesize");
	if (!pagenode.isMissingNode() && !pagenode.isNull() && !pagenode.asText().isEmpty()) {

	    // TODO errorhandline
	    sq.pageSize = pagenode.asInt();
	}

	var querynode = json.at("/query");
		
	if (! querynode.isMissingNode() && ! querynode.isNull() && !querynode.asText().isEmpty() ) {
	    sq.queryString = querynode.asText();
	}

	var facetpagenode = json.at("/facetpagesize");
	if (!facetpagenode.isMissingNode() && !facetpagenode.isNull() && !facetpagenode.asText().isEmpty()) {

	    // TODO errorhandline
	    sq.facetPageSize = facetpagenode.asInt();
	}

	
	for (var filter : json.at("/facetfilters")) {

	    var dim = "";
	    var path = new LinkedList<String>();
	     		    
	    var elems = filter.elements();
	    while (elems.hasNext()){			
		var elem = elems.next();
		
		if (dim.isEmpty()) {
		    dim = elem.asText();
		} else {
		    path.add(elem.asText());
		}
	    }
	    
	    if (! dim.isEmpty() && path.size() > 0){
		var patharr = new String[path.size()];
		patharr = path.toArray(patharr);
		sq.facetfilters.add(sq.new PathFilter(dim, patharr));
	    }
	}	

	return sq;
    }

    public static class SearchQuery {

	public String queryid;
	public Integer pageSize;
	public String queryString;
	
	public LinkedList<PathFilter> facetfilters;
	
	public  class PathFilter {
	    public String dimension;
	    public String[] path;
	    	
	    PathFilter(String dim, String[] path) {
		dimension = dim;
		this.path = path;
	    }
	}

	public Integer facetPageSize;
    }
	
    
    public ByteBuf search(JsonNode json)
	throws IOException, InterruptedException
    {
	// TODO: open the readers once 
	
	var indexReader = DirectoryReader.open(_searcher.indexer.dir);
	var taxoReader = new DirectoryTaxonomyReader(_searcher.indexer.taxdir);
	var indexSearcher = new IndexSearcher(indexReader);
	
	ScoreDoc[] hits = null;
	ByteBuf bodybuf = Unpooled.directBuffer(8);

	try {
            var byteoutput = new ByteBufOutputStream(bodybuf);
            var gen = _searcher.mapper.getFactory().createGenerator((OutputStream)byteoutput);      
            
            gen.writeStartObject();

	    var searchquery = parseQuery(json);

	    if ( searchquery.queryid.isEmpty() == false) {
		
                // Continue a stored query
                // TODO: clear the queries afterwards
		
                var searchstate = searchstates.get(searchquery.queryid);		
                var docs = indexSearcher.searchAfter(searchstate.scoredoc, searchstate.query, searchquery.pageSize);
		
                hits = docs.scoreDocs;
		
                searchstate.scoredoc = hits[hits.length - 1];
                searchstates.put(searchquery.queryid, searchstate);
		
                gen.writeStringField("qid", searchquery.queryid);
                gen.writeStringField("hits", Long.toString(docs.totalHits.value()));
		
            } else {
		
                // Create a new query with facets
		
                var querybuilder = new BooleanQuery.Builder();
                var analyzer = new StandardAnalyzer();
                var parser = new QueryParser("uuid", analyzer);
		
		// TODO: change to an excluding filter, getting rid of series and subseries
		
		querybuilder.add(new TermQuery(new Term("type", "file")), BooleanClause.Occur.FILTER);

		if (searchquery.queryString.isEmpty() == false) {
                    querybuilder.add(parser.parse(searchquery.queryString), BooleanClause.Occur.MUST);
		}
		
                var query = querybuilder.build();
                var dq = new DrillDownQuery(_searcher.indexer.fconfig, query);

		for (var filter : searchquery.facetfilters){
		    dq.add(filter.dimension, filter.path);
		}
	
		var result = new DrillSideways(indexSearcher, _searcher.indexer.fconfig, taxoReader).search(dq, searchquery.pageSize);
                hits = result.hits.scoreDocs;
		
                if (hits.length == 0) {
                    gen.writeNumberField("hits", 0);
		    
                } else {
                    // results; store query and gather facets
		    
                    var queryuuid = Generators.timeBasedGenerator().generate();
		    var searchstate = new SearchState(dq, hits[hits.length - 1]);
		    
                    searchstates.put(queryuuid.toString(), searchstate);
		    		    
                    gen.writeStringField("qid", queryuuid.toString());
		    gen.writeNumberField("hits", result.hits.totalHits.value());
                    gen.writeArrayFieldStart("facets");

		    // TODO: this is only one facet
		    
		    var parents = result.facets.getAllChildren("parents");
		    
		    for (var lv : parents.labelValues) {
			gen.writeStartObject();
			gen.writeStringField("field", "parents");
			gen.writeStringField("uuid", lv.label);
			gen.writeNumberField("count", lv.value.intValue() );

			// TODO: can this be done faster?
			var res = indexSearcher.search(new TermQuery(new Term("uuid", lv.label)), 1);
			for (var hit : res.scoreDocs) {
			    var doc = indexSearcher.storedFields().document(hit.doc);
			    
			    var title = doc.get("title");
			    gen.writeStringField("title", title);
		       }
			gen.writeEndObject();
		    }
		    
                    gen.writeEndArray();
                }
            }
	    
            if (hits.length > 0) {
                gen.writeArrayFieldStart("docs");
		
		for (var hit : hits) {
		    var doc = indexSearcher.storedFields().document(hit.doc);
		    var title = doc.get("title");
		    var uuid = doc.get("uuid");
		  
					
		    gen.writeString(title);

		    // TODO: highlights

		}
		gen.writeEndArray();
            }
	    
            gen.writeEndObject();
            gen.close();

	    // TODO: is this correct here?
	    byteoutput.close();

	    return bodybuf;
	    
        } catch (Exception e) {
            System.out.println(e.toString());
            System.out.println(Arrays.toString(e.getStackTrace()));
	    return bodybuf;
        } finally {
	    IOUtils.close(indexReader, taxoReader);
	}
    }
}
