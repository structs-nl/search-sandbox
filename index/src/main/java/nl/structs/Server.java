package nl.structs;

import static io.netty.handler.codec.http.HttpResponseStatus.OK;
import static io.netty.handler.codec.http.HttpResponseStatus.NOT_FOUND;
import static io.netty.handler.codec.http.HttpVersion.HTTP_1_1;

import java.io.IOException;

import java.io.InputStream;
import java.io.File;

import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;

import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Date;
import org.apache.lucene.document.DateTools;

import java.nio.file.Path;
import java.nio.file.Paths;

import java.io.OutputStream;

import java.util.HashMap;

import java.util.Arrays;
import java.util.LinkedList;

import com.fasterxml.jackson.databind.JsonNode;


import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.stream.ChunkedWriteHandler;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufOutputStream;

import io.netty.handler.stream.ChunkedStream;
import io.netty.handler.stream.ChunkedFile;

import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.handler.codec.http.HttpContentCompressor;

import io.netty.handler.logging.LogLevel;
import io.netty.handler.logging.LoggingHandler;


import java.util.List;
import java.util.ArrayList;

import org.apache.lucene.facet.FacetResult;
import org.apache.lucene.facet.Facets;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.Document;

import org.apache.lucene.facet.LabelAndValue;
import org.apache.lucene.queryparser.classic.QueryParser;

import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.facet.taxonomy.TaxonomyReader;
import org.apache.lucene.facet.taxonomy.directory.DirectoryTaxonomyReader;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.search.SearcherManager;
import org.apache.lucene.search.SearcherFactory;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.index.Term;

import org.apache.lucene.facet.DrillDownQuery;
import org.apache.lucene.facet.DrillSideways;

import org.apache.lucene.search.IndexSearcher;

import org.apache.lucene.index.IndexReader;

import org.apache.lucene.search.TermRangeQuery;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.MatchAllDocsQuery;
import org.apache.lucene.search.BooleanQuery.*;
import org.apache.lucene.facet.DrillDownQuery;
import org.apache.lucene.facet.DrillSideways.DrillSidewaysResult;
import org.apache.lucene.facet.FacetsCollector;
import org.apache.lucene.facet.FacetsCollectorManager;
import org.apache.lucene.facet.taxonomy.FastTaxonomyFacetCounts;

import org.apache.lucene.facet.FacetsCollectorManager.FacetsResult;

import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.TopDocs;


import org.apache.lucene.index.MultiTerms;
import org.apache.lucene.index.PostingsEnum;
import org.apache.lucene.util.BytesRef;

import org.apache.lucene.util.IOUtils;

import org.apache.lucene.index.StoredFields;


import org.apache.lucene.search.ScoreDoc;

import com.fasterxml.uuid.Generators;

import io.netty.buffer.Unpooled;
import io.netty.util.CharsetUtil;

public final class Server {

    private nl.structs.Searcher Searcher;

    private HashMap<String, SearchState> searchstates = new HashMap<String, SearchState>();
    public Server(int PORT, Searcher searcher) 
    throws URISyntaxException, IOException, InterruptedException
    {
        Searcher = searcher;

        var bossGroup = new NioEventLoopGroup(1);
        var workerGroup = new NioEventLoopGroup();
        
        try {
            var b = new ServerBootstrap();

            b.option(ChannelOption.SO_BACKLOG, 1024);
            b.group(bossGroup, workerGroup)
                    .channel(NioServerSocketChannel.class)
                    .handler(new LoggingHandler(LogLevel.INFO))
                    .childHandler(new HTTPInitializer());

            var ch = b.bind(PORT).sync().channel();

            ch.closeFuture().sync();
        } catch (InterruptedException e) {
            e.printStackTrace();
        } finally { 
            System.out.println("Stop!");
            bossGroup.shutdownGracefully();
            workerGroup.shutdownGracefully();
        }
    }
    
    protected class MinMax {
        protected String[] min = null;
        protected String[] max = null;
    }

    protected MinMax minmaxFacets(Facets facets, FacetResult facet , int pagesize, String... path)
    throws IOException
    {

        String min = null;
        String max = null;
        
        for (int j = 0; j < facet.labelValues.length; j++) {
            var lv = facet.labelValues[j];

            if (max == null || max.compareTo(lv.label) < 0)
                max = lv.label;

            if (min == null || min.compareTo(lv.label) > 0)
                min = lv.label;
        }

        String[] minpath = Arrays.copyOf(path, path.length + 1);
        minpath[minpath.length-1] = min;

        String[] maxpath = Arrays.copyOf(path, path.length + 1);
        maxpath[maxpath.length-1] = max;

        MinMax minmax = new MinMax();   
        minmax.min = minpath;
        minmax.max = maxpath; 

        var minfacets = facets.getTopChildren(pagesize, facet.dim, minpath);
        if (minfacets != null){
            MinMax minMinMax = minmaxFacets(facets, minfacets, pagesize, minpath);
            minmax.min = minMinMax.min;
        }

        var maxfacets = facets.getTopChildren(pagesize, facet.dim, maxpath);
        if (maxfacets != null){
            MinMax maxMinMax = minmaxFacets(facets, maxfacets, pagesize, maxpath);
            minmax.max = maxMinMax.max;
        }

        return minmax;
    }
    protected void search(JsonNode json, ChannelHandlerContext ctx, HttpRequest httpRequest)
    throws IOException, InterruptedException
    {

	var indexReader = DirectoryReader.open(Searcher.indexer.dir);
	var taxoReader = new DirectoryTaxonomyReader(Searcher.indexer.taxdir);
	var searcher = new IndexSearcher(indexReader);
	var storedFields = searcher.storedFields();

        try {

            var qidnode = json.at("/qid");
            var pagenode = json.at("/pagesize");
            // TODO: must always be set

            var pagesize = pagenode.asInt();

            ScoreDoc[] hits = null;
            
            var bodybuf = Unpooled.directBuffer(8);
            var byteoutput = new ByteBufOutputStream(bodybuf);
            var gen = Searcher.mapper.getFactory().createGenerator((OutputStream)byteoutput);      
            
            gen.writeStartObject();

            if (!qidnode.isMissingNode() && !qidnode.isNull() && !qidnode.asText().isEmpty()) {

                // Continue a stored query
                // TODO: clear the queries afterwards

                var queryid = qidnode.asText();
                SearchState searchstate = searchstates.get(queryid);

                TopDocs docs = searcher.searchAfter(searchstate.scoredoc, searchstate.query, pagesize);

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
		
                var dq = new DrillDownQuery(Searcher.indexer.fconfig, query);

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

		var result = new DrillSideways(searcher, Searcher.indexer.fconfig, taxoReader).search(dq, pagesize);
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
                    searchstates.put(queryuuid.toString(), new SearchState(dq, hits[hits.length - 1]));

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
			
			var res = searcher.search(new TermQuery(new Term("uuid", lv.label)), 1);
			for (var hit : res.scoreDocs) {
			    var doc = storedFields.document(hit.doc);
			    var title = doc.get("title");
			    gen.writeStringField("title", title);
			}
			gen.writeEndObject();
		    }
		    
		    // List<FacetResult> results = new ArrayList<>();
		    //  results = result.facets.getAllDims(facetpagenode.asInt());
		    // for (int i = 0; i < results.size(); i++) {
		    //     FacetResult facet = results.get(i);
		    //     gen.writeObjectFieldStart(facet.dim);

		    //     MinMax minmax = minmaxFacets(result.facets, facet, facetpagenode.asInt());
		    //gen.writeStringField("min", String.join( "/", minmax.min));
		    //gen.writeStringField("max", String.join( "/", minmax.max));

                    //    gen.writeObjectFieldStart("values");
                    //    for (int j = 0; j < facet.labelValues.length; j++) {
                    //        LabelAndValue lv = facet.labelValues[j];
                    //        gen.writeNumberField(lv.label, lv.value.intValue() );
                    //    }
                    //    gen.writeEndObject();
                    //    gen.writeEndObject();
                    //}
		 
                    gen.writeEndArray();
                }
            }

            if (hits.length > 0) {
                gen.writeArrayFieldStart("docs");
		
		for (var hit : hits) {
		    var doc = storedFields.document(hit.doc);
		    var title = doc.get("title");
		    var uuid = doc.get("uuid");
					
		    gen.writeString(title);
		}
		gen.writeEndArray();
            }

            gen.writeEndObject();
            gen.close();

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
    
    protected class SearchState {
        public Query query;
        public ScoreDoc scoredoc;
        SearchState(Query q, ScoreDoc sd) {
            query = q;
            scoredoc = sd;
        }
    }
    protected class HttpServerHandler extends SimpleChannelInboundHandler<FullHttpRequest> {
        @Override
        public void channelRead0(ChannelHandlerContext ctx, FullHttpRequest httpRequest)
        throws Exception
        {
            if (httpRequest.method().equals(HttpMethod.OPTIONS)) {

                HttpResponse response = new DefaultHttpResponse(HTTP_1_1, OK);
                response.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN, "*");
                response.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_METHODS, "GET, POST, PUT");
                ctx.write(response);

                ChannelFuture lastContentFuture = ctx.writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT);
                lastContentFuture.addListener(ChannelFutureListener.CLOSE);

            } else if (httpRequest.method().equals(HttpMethod.PUT)) {
                if (httpRequest.uri().startsWith("/query")) {
                    ByteBuf data = httpRequest.content();
                    JsonNode query = Searcher.mapper.readTree((data.toString(StandardCharsets.UTF_8)));
		    search(query, ctx, httpRequest);

		    



		    
                }
            }
        }
    }
        
    protected class HTTPInitializer extends ChannelInitializer<SocketChannel> {        
        protected void initChannel(SocketChannel socketChannel) throws Exception {
            ChannelPipeline pipeline = socketChannel.pipeline();
            pipeline.addLast("codec", new HttpServerCodec());
            pipeline.addLast("aggregator", new HttpObjectAggregator(Short.MAX_VALUE));
            pipeline.addLast("chunked", new ChunkedWriteHandler());
            //pipeline.addLast("compressor", new HttpContentCompressor());
            pipeline.addLast("httpHandler", new HttpServerHandler());
        }
    }
}
