package nl.structs;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.Date;
import java.util.LinkedList;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.uuid.Generators;

import io.netty.buffer.ByteBufOutputStream;
import io.netty.buffer.Unpooled;
import io.netty.buffer.ByteBuf;

import org.apache.lucene.facet.taxonomy.directory.DirectoryTaxonomyReader;
import org.apache.lucene.facet.DrillDownQuery;
import org.apache.lucene.facet.DrillSideways;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.queryparser.flexible.standard.StandardQueryParser;

import org.apache.lucene.util.IOUtils;

import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.Query;

import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TopDocs;

import org.apache.lucene.facet.FacetsConfig;

import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.uhighlight.UnifiedHighlighter;
import org.apache.lucene.store.FSDirectory;

import nl.structs.HighlightsFormatter.HighlightResult;
import nl.structs.Querier.SearchQuery.PathFilter;

public class Querier {

  protected ObjectMapper mapper;
  protected FacetsConfig fconfig;
  protected IndexSearcher indexSearcher;
  protected DirectoryReader indexReader;
  protected Analyzer analyzer;
  protected HighlightsAsObject highlighter;
  protected DirectoryTaxonomyReader taxoReader;

  protected SearchStates searchstates;

  public Querier(FSDirectory indexdir, FSDirectory taxdir, ObjectMapper mapper, FacetsConfig fconfig)
      throws IOException, InterruptedException {

    this.mapper = mapper;
    this.fconfig = fconfig;

    indexReader = DirectoryReader.open(indexdir);
    taxoReader = new DirectoryTaxonomyReader(taxdir);
    indexSearcher = new IndexSearcher(indexReader);
    analyzer = new StandardAnalyzer();
    searchstates = new SearchStates();

    highlighter = new HighlightsAsObject(UnifiedHighlighter.builder(indexSearcher, analyzer)
        .withMaxLength(1000000000) // is there a better way of doing this?
        .withFormatter(new HighlightsFormatter()));
  }

  public void close() throws IOException {
    IOUtils.close(indexReader, taxoReader);
  }


  public class SearchStates {
    private Map<String, SearchState> states = new HashMap<String, SearchState>();

    public void cleanup() {

      // TODO: only check max once per hour

      var current = new Date().getTime();
      for (var state: states.entrySet()) {
        var age = current - state.getValue().timestamp.getTime();
        if (age > 86400 * 1000  /* 1 day; perhaps shorten to 6 hours */) {
          states.remove(state.getKey());
        }
      }
    }

    public SearchState add(Query query, ScoreDoc doc) {
        var state =  new SearchState(query, doc);
        states.put(state.uuid.toString(), state);
        return state;
    }

    public SearchState get(String uuid) {
      return states.get(uuid);
    }

    protected class SearchState {
      public Query query;
      public ScoreDoc doc;
      public Date timestamp;
      public UUID uuid;

      SearchState(Query q, ScoreDoc doc) {
        query = q;
        this.doc = doc;
        this.timestamp = new Date();
        this.uuid = Generators.timeBasedGenerator().generate();
      }
    }
  }

  public static class SearchQuery {

    public String queryid = "";
    public Integer pageSize;
    public String queryString = "";

    public LinkedList<PathFilter> facetfilters = new LinkedList<PathFilter>();
    public LinkedList<String> facetdimensions = new LinkedList<String>();

    public SearchQuery(JsonNode json) {
      // TODO error handling

      var qidnode = json.at("/qid");
      if (!qidnode.isMissingNode() && !qidnode.isNull() && !qidnode.asText().isEmpty()) {
        this.queryid = qidnode.asText();
        // TODO: ignore the rest, except the pagesize
      }

      var pagenode = json.at("/pagesize");
      if (!pagenode.isMissingNode() && !pagenode.isNull() && !pagenode.asText().isEmpty()) {
        this.pageSize = pagenode.asInt();
      }

      var querynode = json.at("/query");
      if (!querynode.isMissingNode() && !querynode.isNull() && !querynode.asText().isEmpty()) {
        this.queryString = querynode.asText();
      }

      for (var facet : json.at("/facets")) {

        var dimnode = facet.at("/dimension");
        if (!dimnode.isMissingNode() && !dimnode.isNull() && !dimnode.asText().isEmpty()) {
          this.facetdimensions.add(dimnode.asText());

          var filters = facet.at("/filters");
          if (!filters.isMissingNode() && !filters.isNull() && filters.isArray()) {
            for (var filter: filters) {
              if (!filter.isMissingNode() && !filter.isNull() && filter.isArray()) {
                var path = new LinkedList<String>();
                for (var pathnode : filter) {
                  if (!pathnode.isMissingNode() && !pathnode.isNull() && !pathnode.asText().isEmpty()) {
                    path.add(pathnode.asText());
                  }
                }
                facetfilters.add(new PathFilter(dimnode.asText(), path.toArray(new String[path.size()])));
              }
            }
          }
        }
      }
    }

    public class PathFilter {
      public String dimension;
      public String[] path;

      PathFilter(String dim, String[] path) {
        dimension = dim;
        this.path = path;
      }
    }
  }

  public ByteBuf search(ByteBuf data)
      throws IOException, InterruptedException {

    TopDocs topdocs = null;
    Query currentQuery = null;

    // TODO Check if the index should be re-opened after a write operation. We don't need that for now

    var bodybuf = Unpooled.directBuffer(8);
    var byteoutput = new ByteBufOutputStream(bodybuf);

    try {
      var json = mapper.readTree((data.toString(StandardCharsets.UTF_8)));
      var searchquery = new SearchQuery(json);

      var gen = mapper.getFactory().createGenerator((OutputStream) byteoutput);
      gen.writeStartObject();

      if (searchquery.queryid.isEmpty() == false) {

        // Continue a stored query
        
        var searchstate = searchstates.get(searchquery.queryid);
        topdocs = indexSearcher.searchAfter(searchstate.doc, searchstate.query, searchquery.pageSize);
        searchstate.doc = topdocs.scoreDocs[topdocs.scoreDocs.length - 1];

        gen.writeStringField("qid", searchquery.queryid);
        gen.writeStringField("hits", Long.toString(topdocs.totalHits.value()));

      } else {

        // Create a new query with facets

        var querybuilder = new BooleanQuery.Builder();
        var standardparser = new StandardQueryParser(analyzer);

        // TODO: filter out the dimensions
        // querybuilder.add(new TermQuery(new Term("type", "file")),
        // BooleanClause.Occur.FILTER);

        if (searchquery.queryString.isEmpty() == false) {
          querybuilder.add(standardparser.parse(searchquery.queryString, "content"), BooleanClause.Occur.MUST);
        }

        var query = querybuilder.build();
        var dq = new DrillDownQuery(fconfig, query);

        // TODO test the facet filters

        for (var filter : searchquery.facetfilters) {
          if (filter.path.length > 0) {
            dq.add(filter.dimension, filter.path);
          }
        }

        var result = new DrillSideways(indexSearcher, fconfig, taxoReader).search(dq, searchquery.pageSize);

        topdocs = result.hits;
        currentQuery = dq;

        gen.writeNumberField("hits", topdocs.totalHits.value());

        if (topdocs.scoreDocs.length > 0) {

          // Store the query and output the facets. this is only done for new, not for continued queries

          var searchstate = searchstates.add(currentQuery, topdocs.scoreDocs[topdocs.scoreDocs.length - 1]);
          gen.writeStringField("qid", searchstate.uuid.toString());
          gen.writeArrayFieldStart("facets");

          for (var dimension : searchquery.facetdimensions) {

              // TODO: put hierarchical facets in nested documents: one per dimension
              /* 
              var res = indexSearcher.search(new TermQuery(new Term("uuid", lv.label)), 1);
              for (var hit : res.scoreDocs) {
                var doc = indexSearcher.storedFields().document(hit.doc);
                gen.writeStringField("title", doc.get("title"));
              }
              */

            gen.writeStartObject();
            gen.writeStringField("dimension", dimension);
            writeFacetsRecurse(gen, result.facets, dimension);
            gen.writeEndObject();
          }
          gen.writeEndArray();
        }
      }

      if (topdocs.scoreDocs.length > 0) {

        // Document result rendering. This is used for new and continued queries.
        // TODO the number of highlights should be part of the query

        var high = highlighter.highlight(new String[] { "content" }, currentQuery, topdocs.scoreDocs,
            new int[] { 100 });
        var contentHighlights = high.get("content");

        gen.writeArrayFieldStart("docs");

        for (var i = 0; i < topdocs.scoreDocs.length; i++) {
          var hit = topdocs.scoreDocs[i];
          var doc = indexSearcher.storedFields().document(hit.doc);

          gen.writeStartObject();

          // TODO: config which fields to output

          var title = doc.get("title");
          var uuid = doc.get("uuid");

          gen.writeStringField("title", title);
          gen.writeStringField("uuid", uuid);

          gen.writeArrayFieldStart("highlights");
          @SuppressWarnings("unchecked")
          var docHighlights = (LinkedList<HighlightResult>) contentHighlights[i];

          for (var highlight : docHighlights) {
            gen.writeStartObject();
            gen.writeNumberField("start", highlight.start);
            gen.writeNumberField("end", highlight.end);
            gen.writeStringField("term", highlight.term);
            gen.writeStringField("text", highlight.text);
            gen.writeStringField("prefix", highlight.prefix);
            gen.writeStringField("suffix", highlight.suffix);
            gen.writeEndObject();
          }

          gen.writeEndArray();
          gen.writeEndObject();

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
    }
  }

  private void writeFacetsRecurse(com.fasterxml.jackson.core.JsonGenerator gen, org.apache.lucene.facet.Facets facets, String dimension, String... path) throws IOException {
    
    var result = facets.getAllChildren(dimension, path);
    if (result == null) return;

    gen.writeArrayFieldStart("children");
    for (var lv : result.labelValues) {
      gen.writeStartObject();
      gen.writeStringField("label", lv.label);
      gen.writeNumberField("count", lv.value.intValue());

      var childPath = Arrays.copyOf(path, path.length + 1);
      childPath[path.length] = lv.label;
    
      writeFacetsRecurse(gen, facets, dimension, childPath);
      
      gen.writeEndObject();
    }
    gen.writeEndArray();
  }
}
