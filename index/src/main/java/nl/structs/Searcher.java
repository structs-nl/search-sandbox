package nl.structs;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.concurrent.ExecutionException;
import java.io.IOException;
import java.net.URISyntaxException;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.databind.JsonNode;

import java.nio.file.Paths;
import java.nio.file.Path;

import java.io.FileOutputStream;
import java.io.OutputStream;
import java.io.FileNotFoundException;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;

import org.apache.commons.cli.*;

public class Searcher {
    protected ObjectMapper mapper = new ObjectMapper();

    protected JsonFactory factory;
    protected Indexer indexer;
    protected String datapath;
    protected JsonNode config;
    private Path configpath;
    protected BufferedWriter logwriter;

    public Searcher(String[] args)
    throws URISyntaxException, IOException, InterruptedException, ExecutionException,
    org.apache.lucene.queryparser.classic.ParseException, ParseException
    {

        Runtime.getRuntime().addShutdownHook(new Thread() {
            public void run() 
            {
                try {
                    System.out.println("\nClose index");
                    indexer.close();
                    System.out.println("Bye!");
                } catch(Exception e) {
                    System.out.println(e.getMessage());
                }
            }
        });

        Options options = new Options();
        options.addOption("path", true, "Data path");
        options.addOption("serve", true, "Start server from port");
        options.addOption("index", true, "index file");

        CommandLineParser parser = new DefaultParser();
        CommandLine cmd = parser.parse( options, args);


        // TODO: check necessary options
        // Helpfile

        if(cmd.hasOption("path")) { 
            datapath = cmd.getOptionValue("path");
        } else {
            return;
        }

        //readConfig();

        //File file = new File(datapath + "/log.txt");
        //if (!file.exists())
        //    file.createNewFile();

        //FileWriter fw = new FileWriter(file, true);
        //logwriter = new BufferedWriter(fw);

	factory = new JsonFactory();
	indexer = new Indexer(datapath);

        if(cmd.hasOption("serve")) {
            String port = cmd.getOptionValue("serve");
            try {
                int portnr = Integer.parseInt(port);
                new Server(portnr, this);
		
            } catch (Exception e) {
                
            }
        }

       if(cmd.hasOption("index")) {
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
    throws FileNotFoundException, IOException
    {
        OutputStream jsonout = new FileOutputStream(configpath.toFile());
        JsonGenerator gen = factory.createGenerator(jsonout);    

        mapper.writerWithDefaultPrettyPrinter().writeValue(gen, config);
        mapper.writeTree(gen, config);
        gen.close();
    }

    protected void readConfig()
    throws FileNotFoundException, IOException
    {
        configpath = Paths.get(datapath + "/config.json");
        if (configpath.toFile().exists()) {
            config = mapper.readTree(configpath.toFile());
        } else
            config = mapper.createObjectNode();
    }
   
    public static void main(String[] args) throws Exception
    {
        new Searcher(args);
    }
}
