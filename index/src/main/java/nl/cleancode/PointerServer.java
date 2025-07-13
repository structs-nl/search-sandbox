package nl.cleancode;

import static io.netty.handler.codec.http.HttpResponseStatus.OK;
import static io.netty.handler.codec.http.HttpResponseStatus.NOT_FOUND;
import static io.netty.handler.codec.http.HttpVersion.HTTP_1_1;

import java.io.IOException;

import java.io.InputStream;
import java.io.File;

import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;

import java.time.LocalDate;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Date;
import org.apache.lucene.document.DateTools;

import java.nio.file.Path;
import java.nio.file.Paths;

import java.io.OutputStream;

import java.util.HashMap;
import java.util.UUID;
import java.util.Base64;

import java.util.Arrays;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.core.JsonGenerator;

import org.apache.http.NameValuePair;
import org.apache.http.client.utils.URIBuilder;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
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
import io.netty.channel.EventLoopGroup;
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

import org.apache.lucene.facet.taxonomy.SearcherTaxonomyManager.SearcherAndTaxonomy;


import org.apache.lucene.search.TermRangeQuery;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.MatchAllDocsQuery;
import org.apache.lucene.search.BooleanQuery.*;
import org.apache.lucene.util.BytesRef;
import org.apache.lucene.facet.DrillDownQuery;
import org.apache.lucene.facet.DrillSideways.DrillSidewaysResult;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.TopDocs;
//import org.apache.lucene.search.TotalHits;

import org.apache.lucene.search.ScoreDoc;

import com.fasterxml.uuid.Generators;

import io.netty.buffer.Unpooled;
import io.netty.util.CharsetUtil;
import nl.cleancode.jPointerStore;

public final class PointerServer {

    private jPointerStore pointerstore;

    private HashMap<String, SearchState> searchstates = new HashMap<String, SearchState>();
    public PointerServer(int PORT, jPointerStore jpointerstore) 
    throws URISyntaxException, IOException, InterruptedException
    {
        pointerstore = jpointerstore;

        EventLoopGroup bossGroup = new NioEventLoopGroup(1);
        EventLoopGroup workerGroup = new NioEventLoopGroup();
        
        try {
            ServerBootstrap b = new ServerBootstrap();

            b.option(ChannelOption.SO_BACKLOG, 1024);
            b.group(bossGroup, workerGroup)
                    .channel(NioServerSocketChannel.class)
                    .handler(new LoggingHandler(LogLevel.INFO))
                    .childHandler(new HTTPInitializer());

            Channel ch = b.bind(PORT).sync().channel();

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
            LabelAndValue lv = facet.labelValues[j];

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

        FacetResult minfacets = facets.getTopChildren(pagesize, facet.dim, minpath);
        if (minfacets != null){
            MinMax minMinMax = minmaxFacets(facets, minfacets, pagesize, minpath);
            minmax.min = minMinMax.min;
        }

        FacetResult maxfacets = facets.getTopChildren(pagesize, facet.dim, maxpath);
        if (maxfacets != null){
            MinMax maxMinMax = minmaxFacets(facets, maxfacets, pagesize, maxpath);
            minmax.max = maxMinMax.max;
        }

        return minmax;
    }
    protected void search(JsonNode json, ChannelHandlerContext ctx, HttpRequest httpRequest)
    throws IOException, InterruptedException
    {
        SearcherAndTaxonomy st = pointerstore.indexer.getIndexSearcher();
        try {

            JsonNode qidnode = json.at("/qid");
            JsonNode pagenode = json.at("/pagesize");
            // TODO: must always be set

            int pagesize = pagenode.asInt();

            ScoreDoc[] hits = null;
            
            ByteBuf bodybuf = Unpooled.directBuffer(8);
            ByteBufOutputStream byteoutput = new ByteBufOutputStream(bodybuf);
            JsonGenerator gen = pointerstore.mapper.getFactory().createGenerator((OutputStream)byteoutput);      
            
            gen.writeStartObject();

            if (!qidnode.isMissingNode() && !qidnode.isNull() && !qidnode.asText().isEmpty()) {

                // Continue a stored query
                // TODO: clear the queries afterwards

                String queryid = qidnode.asText();
                SearchState searchstate = searchstates.get(queryid);

                TopDocs docs = st.searcher.searchAfter(searchstate.scoredoc, searchstate.query, pagesize);

                hits = docs.scoreDocs;

                searchstate.scoredoc = hits[hits.length - 1];
                searchstates.put(queryid, searchstate);

                gen.writeStringField("qid", queryid);
                gen.writeStringField("hits", Long.toString(docs.totalHits));

            } else {

                // Create a new query with facets

                Builder querybuilder = new BooleanQuery.Builder();
                Analyzer analyzer = new StandardAnalyzer();
                QueryParser parser = new QueryParser("/Documents/*/Text", analyzer);

                JsonNode querynode = json.at("/query");

                if (! querynode.isMissingNode() && ! querynode.isNull() && !querynode.asText().isEmpty() ) {
                    querybuilder.add(parser.parse(querynode.asText()), BooleanClause.Occur.MUST);
                } else {
                    querybuilder.add(new MatchAllDocsQuery(), BooleanClause.Occur.MUST);
                }

                // TODO: some assumptions about the filter structure. Check them.
                // Only used for a < & > filter expression

                for (JsonNode filter : json.at("/filters")) {
                    JsonNode op = filter.get(0);
                    JsonNode arg1 = filter.get(1);
                    JsonNode arg2 = filter.get(2);

                    if (op.asText().equals("smallerthan") || op.asText().equals("greaterthan")){
                                                
                        LocalDate localDate = LocalDate.parse(arg2.asText(), DateTimeFormatter.ofPattern("dd-MM-yyyy"));
                        ZonedDateTime zonedDateTime = localDate.atStartOfDay(ZoneId.systemDefault());
                        String encodedString = DateTools.dateToString(Date.from(zonedDateTime.toInstant()), DateTools.Resolution.MILLISECOND);

                        String encodedString_ = null;
                    
                        for (JsonNode filter_ : json.at("/filters")) {
                            JsonNode op_ = filter_.get(0);
                            JsonNode arg1_ = filter_.get(1);
                            JsonNode arg2_ = filter_.get(2);

                            // Same field, not the same operator
                            if (arg1.asText().equals(arg1_.asText()) &&  ! op.asText().equals(op_.asText())) {

                                LocalDate localDate_ = LocalDate.parse(arg2_.asText(), DateTimeFormatter.ofPattern("dd-MM-yyyy"));
                                ZonedDateTime zonedDateTime_ = localDate_.atStartOfDay(ZoneId.systemDefault());
                                encodedString_ = DateTools.dateToString(Date.from(zonedDateTime_.toInstant()), DateTools.Resolution.MILLISECOND);
                                break;
                            }
                        }

                        if (op.asText().equals("greaterthan")) {
                            if (encodedString_ == null)
                                encodedString_ = DateTools.dateToString(Date.from( ZonedDateTime.now().toInstant()), DateTools.Resolution.MILLISECOND);

                            querybuilder.add(new TermRangeQuery(arg1.asText(), new BytesRef(encodedString), new BytesRef(encodedString_), true, true), BooleanClause.Occur.FILTER);
                            break;
                        }

                        if (op.asText().equals("smallerthan")) {
                            if (encodedString_ == null)
                                encodedString_ = DateTools.dateToString(new Date(0), DateTools.Resolution.MILLISECOND);

                            querybuilder.add(new TermRangeQuery(arg1.asText(), new BytesRef(encodedString_), new BytesRef(encodedString), true, true), BooleanClause.Occur.FILTER);
                            break;
                        }
                    }
                }

                Query query = querybuilder.build();

                DrillDownQuery dq = pointerstore.indexer.getDrillDownQuery(query);

                for (JsonNode filter : json.at("/facetfilters")) {
                    JsonNode op = filter.get(0);
                    JsonNode arg1 = filter.get(1);
                    JsonNode arg2 = filter.get(2);

                    if (op.asText().equals("is")){
                        dq.add(arg1.asText(), arg2.asText());
                    }
                }

                DrillSidewaysResult result = pointerstore.indexer.getDrillSideways(st).search(dq, pagesize);

                hits = result.hits.scoreDocs;

                if (hits.length == 0) {
                    // no results
                    gen.writeNumberField("hits", 0);
                } else {
                    // results; store query and gather facets

                    UUID queryuuid = Generators.timeBasedGenerator().generate();
                    searchstates.put(queryuuid.toString(), new SearchState(dq, hits[hits.length - 1]));

                    JsonNode facetpagenode = json.at("/facetpagesize");
                    List<FacetResult> results = new ArrayList<>();
                    results = result.facets.getAllDims(facetpagenode.asInt());

                    gen.writeStringField("qid", queryuuid.toString());
                    gen.writeNumberField("hits", result.hits.totalHits);
                    gen.writeObjectFieldStart("facets");

                    for (int i = 0; i < results.size(); i++) {
                        FacetResult facet = results.get(i);
                        gen.writeObjectFieldStart(facet.dim);

                        MinMax minmax = minmaxFacets(result.facets, facet, facetpagenode.asInt());
                        gen.writeStringField("min", String.join( "/", minmax.min));
                        gen.writeStringField("max", String.join( "/", minmax.max));

                        gen.writeObjectFieldStart("values");
                        for (int j = 0; j < facet.labelValues.length; j++) {
                            LabelAndValue lv = facet.labelValues[j];
                            gen.writeNumberField(lv.label, lv.value.intValue() );
                        }
                        gen.writeEndObject();
                        gen.writeEndObject();
                    }
                    gen.writeEndObject();
                }
            }

            if (hits.length > 0) {
                // output docs for new or existing query
                
                gen.writeArrayFieldStart("docs");

                int end = (int)Math.min(hits.length, pagesize);
                for (int i = 0; i < end; i++) {
                    Document doc = st.searcher.doc(hits[i].doc);

                    String uuid = doc.get("uuid");

		    // TODO: create and write the jsontree
                    // pointerstore.mapper.writeTree(gen, jsontree);
                    
                }
                gen.writeEndArray();
            }

            gen.writeEndObject();
            gen.close();

            HttpResponse response = new DefaultHttpResponse(HTTP_1_1, OK);
            response.headers().set(HttpHeaderNames.CONTENT_TYPE, HttpHeaderValues.APPLICATION_JSON);
            response.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN, "*");
            response.headers().set(HttpHeaderNames.CONTENT_LENGTH, bodybuf.readableBytes());

            if (HttpUtil.isKeepAlive(httpRequest))
                response.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.KEEP_ALIVE);

            ctx.write(response);
            ctx.write(bodybuf);

            byteoutput.close();

            ChannelFuture lastContentFuture = ctx.writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT);
            if (!HttpUtil.isKeepAlive(httpRequest))
                lastContentFuture.addListener(ChannelFutureListener.CLOSE);

        } catch (Exception e) {
            System.out.println(e.toString());
            System.out.println(Arrays.toString(e.getStackTrace()));
        } finally {
            pointerstore.indexer.releaseIndexSearcher(st);
            st = null;
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
                    JsonNode query = pointerstore.mapper.readTree((data.toString(StandardCharsets.UTF_8)));
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
