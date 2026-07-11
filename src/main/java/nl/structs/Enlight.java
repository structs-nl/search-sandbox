package nl.structs;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.*;
import io.netty.handler.codec.http.multipart.*;

import io.netty.handler.logging.LogLevel;
import io.netty.handler.logging.LoggingHandler;

import static io.netty.handler.codec.http.HttpResponseStatus.BAD_REQUEST;
import static io.netty.handler.codec.http.HttpResponseStatus.OK;
import static io.netty.handler.codec.http.HttpVersion.HTTP_1_1;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;

import java.util.concurrent.ExecutionException;
import java.net.URISyntaxException;

import java.io.IOException;
import java.io.BufferedWriter;

import java.nio.file.Paths;

import org.apache.commons.cli.*;
import org.apache.lucene.store.FSDirectory;

public class Enlight {

  protected ObjectMapper mapper = new ObjectMapper();

  protected Suggester suggester;
  protected Indexer indexer;
  protected Querier querier;
  protected String datapath;
  protected FSDirectory indexdir;
  protected FSDirectory taxdir;
  protected FSDirectory suggestdir;
  protected BufferedWriter logwriter;

  public Enlight(String[] args)
      throws URISyntaxException, IOException, InterruptedException, ExecutionException,
      org.apache.lucene.queryparser.classic.ParseException, ParseException {

    Runtime.getRuntime().addShutdownHook(new Thread() {
      public void run() {
        try {
          System.out.println("\nClose index");
          indexer.close();
          System.out.println("Bye!");
        } catch (Exception e) {
          System.out.println(e.getMessage());
        }
      }
    });

    // TODO remove these commandline options. Replace with ENV vars
    // TODO: add a readonly option. This will disable ingesting

    Options options = new Options();
    options.addOption("path", true, "Data path");
    options.addOption("port", true, "Start server from port");

    CommandLineParser parser = new DefaultParser();
    CommandLine cmd = parser.parse(options, args);

    if (cmd.hasOption("path")) {
      datapath = cmd.getOptionValue("path");
      indexdir = FSDirectory.open(Paths.get(datapath + "/index/"));
      taxdir = FSDirectory.open(Paths.get(datapath + "/tax/"));
      suggestdir = FSDirectory.open(Paths.get(datapath + "/suggest/"));

      // TODO check if the directories exist. Create if not so

    } else {
      // Error message
      return;
    }

    // File file = new File(datapath + "/log.txt");
    // if (!file.exists())
    // file.createNewFile();

    // FileWriter fw = new FileWriter(file, true);
    // logwriter = new BufferedWriter(fw);

    indexer = new Indexer(indexdir, taxdir);
    querier = new Querier(indexdir, taxdir, mapper, indexer.fconfig);
    suggester = new Suggester(suggestdir);

    // suggester.ingest("./testdata/autocomplete_places.csv");

    if (cmd.hasOption("port")) {

      var port = cmd.getOptionValue("port");
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

    System.exit(0);
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

      var responseStatus = OK;
      var responseMessage = "";

    try {
      if (httpRequest.method().equals(HttpMethod.OPTIONS)) {

        HttpResponse response = new DefaultHttpResponse(HTTP_1_1, OK);
        // response.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN, "*");
        response.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_METHODS, "GET, POST, PUT");
        ctx.write(response);

        ChannelFuture lastContentFuture = ctx.writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT);
        lastContentFuture.addListener(ChannelFutureListener.CLOSE);

      } else if (httpRequest.method().equals(HttpMethod.PUT)) {
        if (httpRequest.uri().startsWith("/query")) {

          var bodybuf = querier.search(httpRequest.content());

          var response = new DefaultHttpResponse(HTTP_1_1, OK);
          response.headers().set(HttpHeaderNames.CONTENT_TYPE, HttpHeaderValues.APPLICATION_JSON);
          // response.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN, "*");
          ctx.write(response);
          ctx.write(new DefaultHttpContent(bodybuf));

          var lastContentFuture = ctx.writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT);
          lastContentFuture.addListener(ChannelFutureListener.CLOSE);

          // TODO Add keepalive code
          // TODO Do we need to close the buffer?

        } else if (httpRequest.uri().startsWith("/ingest")) {

          String contentType = httpRequest.headers().get(HttpHeaderNames.CONTENT_TYPE);

          // TODO: we should also be able to handle a URL passed
          // What kind of content type?

          // Handle form-data

          if (contentType != null && (contentType.contains("application/x-www-form-urlencoded")
              || contentType.contains("multipart/form-data"))) {

            HttpPostRequestDecoder decoder = new HttpPostRequestDecoder(httpRequest);
            for (InterfaceHttpData httpData : decoder.getBodyHttpDatas()) {
              if (httpData.getHttpDataType() == InterfaceHttpData.HttpDataType.FileUpload) {

                FileUpload fileUpload = (FileUpload) httpData;
                if (fileUpload.isCompleted()) {

                  var jsonnode = mapper.readTree(fileUpload.getByteBuf().array());
                  indexer.indexDocument(jsonnode);
                  
                } else {
                  responseStatus = BAD_REQUEST;
                  responseMessage = "File upload not completed: " + fileUpload.getFilename();
                  throw new IllegalArgumentException(responseMessage);
                }
              }
            }

            var response = new DefaultHttpResponse(HTTP_1_1, responseStatus);
            // response.headers().set(HttpHeaderNames.CONTENT_TYPE,
            // HttpHeaderValues.APPLICATION_JSON);
            // response.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN, "*");
            ctx.write(response);

            var lastContentFuture = ctx.writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT);
            lastContentFuture.addListener(ChannelFutureListener.CLOSE);

            decoder.cleanFiles();
            decoder.destroy();
          }
        }

      }

      } catch (Exception e) {
        responseStatus = BAD_REQUEST;
        responseMessage = e.getMessage() != null ? e.getMessage() : "Invalid ingest payload";
      }
      
      // TODO: extend error handling.

      if (responseStatus == BAD_REQUEST) {

        var errorBody = mapper.createObjectNode();
        errorBody.put("error", responseMessage);
        var response = new DefaultFullHttpResponse(HTTP_1_1, BAD_REQUEST);
        response.content().writeBytes(errorBody.toString().getBytes(StandardCharsets.UTF_8));
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, HttpHeaderValues.APPLICATION_JSON);
        response.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, response.content().readableBytes());
        ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
      }

        // Add keepalive code

  
        // TODO After the request is handled, clean old search states
        // querier.searchstates.cleanup();
    }
  }

  public static void main(String[] args) throws Exception {
    new Enlight(args);
  }
}