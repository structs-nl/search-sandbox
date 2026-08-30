package nl.structs;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.DefaultHttpContent;
import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpContentCompressor;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.logging.LoggingHandler;

import static io.netty.handler.codec.http.HttpResponseStatus.BAD_REQUEST;
import static io.netty.handler.codec.http.HttpResponseStatus.OK;
import static io.netty.handler.codec.http.HttpVersion.HTTP_1_1;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.JsonParser;

import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;

import java.io.BufferedWriter;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.ExecutionException;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.apache.lucene.facet.FacetsConfig;
import org.apache.lucene.store.FSDirectory;


public class Enlight {

  protected ObjectMapper mapper = new ObjectMapper()
            .configure(JsonParser.Feature.ALLOW_COMMENTS, true)
            .configure(JsonParser.Feature.ALLOW_YAML_COMMENTS, true);

  protected Suggester suggester;
  protected Indexer indexer;
  protected FacetsConfig fconfig;
  protected Querier querier;

  protected FSDirectory indexdir, taxdir, suggestdir;

  protected BufferedWriter logwriter;

  public Enlight(String[] args)
      throws URISyntaxException, IOException, InterruptedException, ExecutionException,
      org.apache.lucene.queryparser.classic.ParseException, ParseException {

    Runtime.getRuntime().addShutdownHook(new Thread() {
      public void run() {
        try {
        
          if (indexer != null ){
            System.out.println("\nClose indexer");
            indexer.close();
          }

          if (querier != null ){
            System.out.println("\nClose querier");
            querier.close();
          }

          if (suggester != null){
            System.out.println("\nClose suggester");
            suggester.close();
          }
 
          System.out.println("Bye!");
        } catch (Exception e) {
          System.out.println(e.getMessage());
        }
      }
    });

    // TODO: add a readonly option. This will disable ingesting

    // TODO add facetconfig path

    Options options = new Options();
    options.addOption(Option.builder("path")
        .hasArg()
        .required(true)
        .desc("Data path")
        .build());

    options.addOption(Option.builder("port")
        .hasArg()
        .required(true)
        .desc("Start server from port")
        .build());

    CommandLineParser parser = new DefaultParser();
    CommandLine cmd;

    try {
      cmd = parser.parse(options, args);
    } catch (ParseException e) {
      System.err.println(e.getMessage());
      HelpFormatter formatter = new HelpFormatter();
      formatter.printHelp("Enlight", options);
      throw e;
    }


    var datapath = cmd.getOptionValue("path");
    var port = cmd.getOptionValue("port");

    var configpath = Paths.get(datapath + "/config.yaml");

    if (!Files.exists(configpath)) {
      // TODO: create a new configfile

    }

    var configfile = configpath.toFile();
    var yamlMapper = new YAMLMapper();
    var config = yamlMapper.readTree(configfile);

    var indexpath = Paths.get(datapath + "/index/");
    ensureDirectoryExists(indexpath);

    var taxpath = Paths.get(datapath + "/tax/");
    ensureDirectoryExists(taxpath);

    Indexer.initializeEmptyIndexesIfNeeded(indexpath, taxpath);

    indexdir = FSDirectory.open(indexpath);
    taxdir = FSDirectory.open(taxpath);

    var suggestpath = Paths.get(datapath + "/suggest/");
    ensureDirectoryExists(suggestpath);
    suggestdir = FSDirectory.open(suggestpath);

    // File file = new File(datapath + "/log.txt");
    // if (!file.exists())
    // file.createNewFile();

    // FileWriter fw = new FileWriter(file, true);
    // logwriter = new BufferedWriter(fw);

    // TODO configurable
    
    fconfig = ConfigHelper.loadFacetConfig(config);

    indexer = new Indexer(indexdir, taxdir, mapper, fconfig);
    querier = new Querier(indexdir, taxdir, mapper, fconfig);
    suggester = new Suggester(suggestdir);

    // suggester.ingest("./testdata/autocomplete_places.csv");

    var bossGroup = new NioEventLoopGroup(1);
    var workerGroup = new NioEventLoopGroup( 10);

    try {

      var portnr = Integer.parseInt(port);

      // TODO: add threads

      var b = new ServerBootstrap();
      b.option(ChannelOption.SO_BACKLOG, 1024);
      b.group(bossGroup, workerGroup)
          .channel(NioServerSocketChannel.class)
          .handler(new LoggingHandler(LogLevel.INFO))
          .childHandler(new HTTPInitializer());

      var ch = b.bind(portnr).sync().channel();

      ch.closeFuture().sync();
      
    } catch (InterruptedException e) {
      e.printStackTrace();
    } finally {
      
      System.out.println("Closing..");

      querier.close();
      suggester.close();
      indexer.close();
      
      bossGroup.shutdownGracefully();
      workerGroup.shutdownGracefully();

      System.out.println("Closed");

    }
  }

  private void ensureDirectoryExists(Path path) throws IOException {
    if (!Files.exists(path)) {
      Files.createDirectories(path);
    }
  }

  protected class HTTPInitializer extends ChannelInitializer<SocketChannel> {
    protected void initChannel(SocketChannel socketChannel) throws Exception {
      ChannelPipeline pipeline = socketChannel.pipeline();
      pipeline.addLast("codec", new HttpServerCodec());
      pipeline.addLast("aggregator", new HttpObjectAggregator(Short.MAX_VALUE));
      pipeline.addLast("compressor", new HttpContentCompressor());
      pipeline.addLast("httpHandler", new HttpServerHandler());
    }
  }

  protected class HttpServerHandler extends SimpleChannelInboundHandler<FullHttpRequest> {
    @Override
    public void channelRead0(ChannelHandlerContext ctx, FullHttpRequest httpRequest)
        throws Exception {

      try {

        if (httpRequest.method().equals(HttpMethod.OPTIONS)) {

          writeAllowMethods(ctx);

        } else if (httpRequest.method().equals(HttpMethod.PUT)) {

          if (httpRequest.uri().startsWith("/query")) {
            // TODO make non blocking

            var bodybuf = querier.search(httpRequest.content());
            write(ctx, OK, bodybuf);

          } else if (httpRequest.uri().startsWith("/suggest")) {

          } else if (httpRequest.uri().startsWith("/ingest/doc")) {
            // TODO make non blocking

            var content = httpRequest.content().toString(StandardCharsets.UTF_8);
            indexer.index(content);
            write(ctx, OK);

          } else if (httpRequest.uri().startsWith("/ingest/suggest")) {

          } else {
            write (ctx, BAD_REQUEST, "Invalid request");
          }

        } else {
            write (ctx, BAD_REQUEST, "Invalid request");
        }

      } catch (Exception e) {
        var responseMessage = e.getMessage() != null ? e.getMessage() : "Invalid request";
        write (ctx, BAD_REQUEST, responseMessage);
        e.printStackTrace(System.out);
      }

      // TODO After the request is handled, clean old search states
      // querier.searchstates.cleanup();
    }
  }

  // TODO Add keepalive code ?
  // TODO Do we need to close the buffer?
  // TODO merge methods?


  private static void writeAllowMethods(ChannelHandlerContext ctx) {
    var response = new DefaultHttpResponse(HTTP_1_1, OK);
    // response.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN, "*");
    response.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_METHODS, "GET, POST, PUT");
    ctx.write(response);
    ctx.writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT).addListener(ChannelFutureListener.CLOSE);
  }

  private static void write(ChannelHandlerContext ctx, HttpResponseStatus responseStatus) {
    var response = new DefaultFullHttpResponse(HTTP_1_1, responseStatus);
    ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
  }

  private static void write(ChannelHandlerContext ctx, HttpResponseStatus responseStatus, String responseMessage) {
    var response = new DefaultFullHttpResponse(HTTP_1_1, responseStatus);
    response.content().writeBytes(responseMessage.getBytes(StandardCharsets.UTF_8));
    response.headers().set(HttpHeaderNames.CONTENT_TYPE, HttpHeaderValues.TEXT_PLAIN);
    response.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, response.content().readableBytes());
    ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
  }

  private static void write(ChannelHandlerContext ctx, HttpResponseStatus responseStatus, ByteBuf bodybuf) {
    var response = new DefaultHttpResponse(HTTP_1_1, responseStatus);
    response.headers().set(HttpHeaderNames.CONTENT_TYPE, HttpHeaderValues.APPLICATION_JSON);
    // response.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN, "*");
    ctx.write(response);
    ctx.write(new DefaultHttpContent(bodybuf));
    ctx.writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT).addListener(ChannelFutureListener.CLOSE);
  }
  
  public static void main(String[] args) throws Exception {
    new Enlight(args);
  }
}