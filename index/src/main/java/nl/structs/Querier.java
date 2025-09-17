package nl.structs;

import java.io.IOException;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.HashMap;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.uuid.Generators;

import static io.netty.handler.codec.http.HttpVersion.HTTP_1_1;
import static io.netty.handler.codec.http.HttpResponseStatus.OK;

import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.buffer.ByteBufOutputStream;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http.*;

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
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.search.ScoreDoc;

import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.Term;

public class Querier {

    protected Searcher _searcher;
    protected HashMap<String, SearchState> searchstates = new HashMap<String, SearchState>();
    
    public Querier(Searcher searcher) {
	
	
    }
    
    protected class SearchState {
	public Query query;
	public ScoreDoc scoredoc;
	
	SearchState(Query q, ScoreDoc sd) {
	    query = q;
	    scoredoc = sd;
	}
    }
    
    public void search(JsonNode json, ChannelHandlerContext ctx, HttpRequest httpRequest)
	throws IOException, InterruptedException
    {
	
	// TODO: open the readers once 
	
	var indexReader = DirectoryReader.open(_searcher.indexer.dir);
	var taxoReader = new DirectoryTaxonomyReader(_searcher.indexer.taxdir);
	var indexSearcher = new IndexSearcher(indexReader);
	
        try {
	    
            var qidnode = json.at("/qid");
            var pagenode = json.at("/pagesize");
            var pagesize = pagenode.asInt();
	    
            ScoreDoc[] hits = null;
            
            var bodybuf = Unpooled.directBuffer(8);
            var byteoutput = new ByteBufOutputStream(bodybuf);
            var gen = _searcher.mapper.getFactory().createGenerator((OutputStream)byteoutput);      
            
            gen.writeStartObject();
	    
            if (!qidnode.isMissingNode() && !qidnode.isNull() && !qidnode.asText().isEmpty()) {
		
                // Continue a stored query
                // TODO: clear the queries afterwards
		
                var queryid = qidnode.asText();
                SearchState searchstate = searchstates.get(queryid);
		
                TopDocs docs = indexSearcher.searchAfter(searchstate.scoredoc, searchstate.query, pagesize);
		
                hits = docs.scoreDocs;
		
                searchstate.scoredoc = hits[hits.length - 1];
                searchstates.put(queryid, searchstate);
		
                gen.writeStringField("qid", queryid);
                gen.writeStringField("hits", Long.toString(docs.totalHits.value()));
		
            } else {
		
                // Create a new query with facets
		
                var querybuilder = new BooleanQuery.Builder();
                var analyzer = new StandardAnalyzer();
                var parser = new QueryParser("uuid", analyzer);
		
		// TODO: change to an excluding filter, getting rid of series and subseries
		querybuilder.add(new TermQuery(new Term("type", "file")), BooleanClause.Occur.FILTER);
                var querynode = json.at("/query");
		
                if (! querynode.isMissingNode() && ! querynode.isNull() && !querynode.asText().isEmpty() ) {
                    querybuilder.add(parser.parse(querynode.asText()), BooleanClause.Occur.MUST);
		//} else {
		    //  querybuilder.add(new MatchAllDocsQuery(), BooleanClause.Occur.MUST);
                }
		
                var query = querybuilder.build();
                var dq = new DrillDownQuery(_searcher.indexer.fconfig, query);

                for (var filter : json.at("/facetfilters")) {

		    // list of lists
		    System.out.println(filter);

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
			dq.add(dim, patharr);
		    }
		}
		
		var result = new DrillSideways(indexSearcher, _searcher.indexer.fconfig, taxoReader).search(dq, pagesize);
                hits = result.hits.scoreDocs;
		
		//var fcm = new FacetsCollectorManager();
		//var result = FacetsCollectorManager.search(searcher, dq, pagesize, fcm);
		//hits = result.topDocs().scoreDocs;
		
                if (hits.length == 0) {
                    // no results
                    gen.writeNumberField("hits", 0);
                } else {
                    // results; store query and gather facets
		    
                    var queryuuid = Generators.timeBasedGenerator().generate();
		    var searchstate = new SearchState(dq, hits[hits.length - 1]);
		    
                    searchstates.put(queryuuid.toString(), searchstate);
		    
                    var facetpagenode = json.at("/facetpagesize");
		    
                    gen.writeStringField("qid", queryuuid.toString());
                    //gen.writeNumberField("hits", result.topDocs().totalHits.value());
		    gen.writeNumberField("hits", result.hits.totalHits.value());
		    
                    gen.writeArrayFieldStart("facets");
		    
		    // var facets = new FastTaxonomyFacetCounts(taxoReader, pointerstore.indexer.fconfig, result.facetsCollector());
		    var facets = result.facets;
		    
		    var parents = facets.getAllChildren("parents");
		    
		    for (var lv : parents.labelValues) {
			gen.writeStartObject();
			gen.writeStringField("field", "parents");
			gen.writeStringField("uuid", lv.label);
			gen.writeNumberField("count", lv.value.intValue() );
			
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
		}
		gen.writeEndArray();
            }
	    
            gen.writeEndObject();
            gen.close();
	    
	    // TODO: move this to the server
	    
            var response = new DefaultHttpResponse(HTTP_1_1, OK);
            response.headers().set(HttpHeaderNames.CONTENT_TYPE, HttpHeaderValues.APPLICATION_JSON);
            response.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN, "*");
            response.headers().set(HttpHeaderNames.CONTENT_LENGTH, bodybuf.readableBytes());

            if (HttpUtil.isKeepAlive(httpRequest))
                response.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.KEEP_ALIVE);
	    
            ctx.write(response);
            ctx.write(bodybuf);
	    
            byteoutput.close();
	    
            var lastContentFuture = ctx.writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT);
            if (!HttpUtil.isKeepAlive(httpRequest))
                lastContentFuture.addListener(ChannelFutureListener.CLOSE);
	    
        } catch (Exception e) {
            System.out.println(e.toString());
            System.out.println(Arrays.toString(e.getStackTrace()));
        } finally {
	    IOUtils.close(indexReader, taxoReader);
	}
    }
}
