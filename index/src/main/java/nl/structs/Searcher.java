package nl.structs;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;

import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.*;

import io.netty.handler.stream.ChunkedWriteHandler;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.logging.LoggingHandler;

import static io.netty.handler.codec.http.HttpResponseStatus.OK;
import static io.netty.handler.codec.http.HttpVersion.HTTP_1_1;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.concurrent.ExecutionException;
import java.net.URISyntaxException;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.databind.JsonNode;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.FileNotFoundException;
import java.io.BufferedWriter;

import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.nio.file.Path;

import org.apache.commons.cli.*;

public class Searcher {
    
    // This class does the following
    // - handle the command line args (serve and index)
    // - start the webserver
    // - handles the http requests (/query and /index)
    // - handle config and logging (currently not used)
    
    // The Indexer and Querier contain the data / app specific code. This can be
    // generalized to abstract classes and app specific instances
    
    protected ObjectMapper mapper = new ObjectMapper();
    
    protected JsonFactory factory;
    protected Indexer indexer;
    protected Querier querier;
    protected String datapath;
    protected JsonNode config;
    private Path configpath;
    protected BufferedWriter logwriter;
    
    public Searcher(String[] args)
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
	
	Options options = new Options();
	options.addOption("path", true, "Data path");
	options.addOption("serve", true, "Start server from port");
	options.addOption("index", true, "index file");
	
	CommandLineParser parser = new DefaultParser();
	CommandLine cmd = parser.parse(options, args);
	
	if (cmd.hasOption("path")) {
	    datapath = cmd.getOptionValue("path");
	} else {
	    return;
	}
	
	// readConfig();
	
	// File file = new File(datapath + "/log.txt");
	// if (!file.exists())
	// file.createNewFile();
	
	// FileWriter fw = new FileWriter(file, true);
	// logwriter = new BufferedWriter(fw);
	
	factory = new JsonFactory();
	indexer = new Indexer(datapath);
	querier = new Querier(this);
	
	if (cmd.hasOption("serve")) {

	    var port = cmd.getOptionValue("serve");
	    var bossGroup = new NioEventLoopGroup(1);
	    var workerGroup = new NioEventLoopGroup();
	    
	    try {
		
		var portnr = Integer.parseInt(port);
		
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
		System.out.println("Stop!");
		bossGroup.shutdownGracefully();
		workerGroup.shutdownGracefully();
	    }
	    
	}

	if (cmd.hasOption("index")) {
	    String path = cmd.getOptionValue("index");
	    try {
		Path indexfile = Paths.get(path);
		System.out.print(path);
		if (indexfile.toFile().exists()) {
		    indexer.index(mapper.readTree(indexfile.toFile()));
		}
		
	    } catch (Exception e) {
		
	    }
	}
	
	System.exit(0);
    }
    
    protected void saveConfig()
	throws FileNotFoundException, IOException {
	OutputStream jsonout = new FileOutputStream(configpath.toFile());
	JsonGenerator gen = factory.createGenerator(jsonout);
	
	mapper.writerWithDefaultPrettyPrinter().writeValue(gen, config);
	mapper.writeTree(gen, config);
	gen.close();
    }
    
    protected void readConfig()
	throws FileNotFoundException, IOException {
	configpath = Paths.get(datapath + "/config.json");
	if (configpath.toFile().exists()) {
	    config = mapper.readTree(configpath.toFile());
	} else
	    config = mapper.createObjectNode();
    }
    
    protected class HTTPInitializer extends ChannelInitializer<SocketChannel> {
	protected void initChannel(SocketChannel socketChannel) throws Exception {
	    ChannelPipeline pipeline = socketChannel.pipeline();
	    pipeline.addLast("codec", new HttpServerCodec());
	    pipeline.addLast("aggregator", new HttpObjectAggregator(Short.MAX_VALUE));
	    pipeline.addLast("chunked", new ChunkedWriteHandler());
	    // pipeline.addLast("compressor", new HttpContentCompressor());
	    pipeline.addLast("httpHandler", new HttpServerHandler());
	}
    }

    protected class HttpServerHandler extends SimpleChannelInboundHandler<FullHttpRequest> {
	@Override
	public void channelRead0(ChannelHandlerContext ctx, FullHttpRequest httpRequest)
	    throws Exception {
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
		    JsonNode query = mapper.readTree((data.toString(StandardCharsets.UTF_8)));
		    
		    querier.search(query, ctx, httpRequest);
		    
		} else if (httpRequest.uri().startsWith("/ingest")) {
		    
		}
	    }
	}
    }
    
    public static void main(String[] args) throws Exception {
	new Searcher(args);
    }
}
