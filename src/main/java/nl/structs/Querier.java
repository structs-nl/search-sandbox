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
import com.fasterxml.jackson.core.JsonGenerator;

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

import org.apache.lucene.search.Query;
import org.apache.lucene.facet.Facets;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TopDocs;

import org.apache.lucene.facet.FacetsConfig;

import org.apache.lucene.index.DirectoryReader;
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

    analyzer = new StandardAnalyzer();
    indexReader = DirectoryReader.open(indexdir);
    taxoReader = new DirectoryTaxonomyReader(taxdir);
    indexSearcher = new IndexSearcher(indexReader);
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
      for (var state : states.entrySet()) {
        var age = current - state.getValue().timestamp.getTime();
        if (age > 86400 * 1000 /* 1 day; perhaps shorten to 6 hours */) {
          states.remove(state.getKey());
        }
      }
    }

    public SearchState add(Query query, ScoreDoc doc) {
      var state = new SearchState(query, doc);
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
    public int pageSize = 15;
    public int passageSize = 10;
    public String queryString = "";
    public boolean continuation = false;
    
    public LinkedList<PathFilter> facetfilters = new LinkedList<PathFilter>();
    public LinkedList<String> facetdimensions = new LinkedList<String>();
    public LinkedList<Field> fields = new LinkedList<Field>();

    public SearchQuery(JsonNode json) {

      var qidnode = json.at("/qid");

      if (!qidnode.isMissingNode() && qidnode.isTextual() && !qidnode.asText().isEmpty()) {
        // Continuing an existing query
        // Only a few aspects of the stored query can be changed when we are dealing
        // with a continuation
        continuation = true;
        this.queryid = qidnode.asText();
      }

      var pagenode = json.at("/pagesize");
      if (!pagenode.isMissingNode() && pagenode.isInt()) {
        this.pageSize = pagenode.asInt();
      }

      var passagenode = json.at("/passagesize");
      if (!passagenode.isMissingNode() && passagenode.isInt()) {
        this.passageSize = passagenode.asInt();
      }

      var fieldsnode = json.at("/fields");
      if (!fieldsnode.isMissingNode() && fieldsnode.isArray()) {
        for (var fieldnode :fieldsnode) {
            var namenode = fieldnode.at("/name");
            var typenode = fieldnode.at("/type");

            if (namenode.isMissingNode() || !namenode.isTextual() || namenode.asText().isEmpty()) {
              // TODO: a field needs a name: error
            }

            if (typenode.isMissingNode() || !typenode.isTextual() || typenode.asText().isEmpty()) {
              // TODO: a field needs a type: error
            }
            
            // TODO: check the types here?

            fields.add(new Field(namenode.asText(),typenode.asText()));
        }
      } else {
        // TODO: is the fields array mandatory?
      }

      if (continuation == false) {

        var querynode = json.at("/query");
        if (!querynode.isMissingNode() && querynode.isTextual() && !querynode.asText().isEmpty()) {
          this.queryString = querynode.asText();
        } else {
          // TODO: no continuation and no query: error
        }


      var facetsnode = json.at("/facets");
      if (!facetsnode.isMissingNode() && facetsnode.isArray()) {

        for (var facet : facetsnode) {

            var dimnode = facet.at("/dimension");
            if (!dimnode.isMissingNode() && dimnode.isTextual() && !dimnode.asText().isEmpty()) {

              this.facetdimensions.add(dimnode.asText());

              var filters = facet.at("/filters");
              // array of arrays of strings

              if (!filters.isMissingNode() && filters.isArray()) {

                for (var filter : filters) {

                  if (!filter.isMissingNode() && filter.isArray()) {
                
                    // the array of strings is encoded in a pathfilter, together with the dimension
                    var path = new LinkedList<String>();

                    for (var pathnode : filter) {
                      if (!pathnode.isMissingNode() && dimnode.isTextual() && !pathnode.asText().isEmpty()) {
                        path.add(pathnode.asText());
                      } else {
                        // TODO: the values of the path array should all be strings: error
                      }
                    }
                    facetfilters.add(new PathFilter(dimnode.asText(), path.toArray(new String[path.size()])));

                  } else {
                    // TODO: filters should be an array: error
                  }
                }
              } else {
                // TODO: no filters on a facet: error
              }
            } else {
              // TODO: no dimension of a facet: error
            }
          }
        }
      }
    }

    public record Field (String name, String type) {}

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

    // TODO Check if the index should be re-opened after a write operation
    // We don't need that for now

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

        if (searchquery.queryString.isEmpty() == false) {
          querybuilder.add(standardparser.parse(searchquery.queryString, "content"), BooleanClause.Occur.MUST);
        }

        var query = querybuilder.build();
        var dq = new DrillDownQuery(fconfig, query);

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

          // Store the query and output the facets. this is only done for new, not for
          // continued queries

          var searchstate = searchstates.add(currentQuery, topdocs.scoreDocs[topdocs.scoreDocs.length - 1]);
          gen.writeStringField("qid", searchstate.uuid.toString());
          gen.writeArrayFieldStart("facets");

          for (var dimension : searchquery.facetdimensions) {
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

        var high = highlighter.highlight(new String[] { "content" }, currentQuery, topdocs.scoreDocs,
            new int[] { searchquery.passageSize });

        var contentHighlights = high.get("content");

        gen.writeArrayFieldStart("docs");

        for (var i = 0; i < topdocs.scoreDocs.length; i++) {
          var hit = topdocs.scoreDocs[i];
          var doc = indexSearcher.storedFields().document(hit.doc);

          gen.writeStartObject();

          for (var field : searchquery.fields) {

            if (field.type.equals("string")) {
              var fieldvalue = doc.get(field.name);
              gen.writeStringField(field.name, fieldvalue);

            } else if (field.type.equals("integer")) {
              // TODO: add support for the integer type
            }
          }

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
      byteoutput.close(); // TODO: I don't think we need to release a reference to to buffer, but check!

      return bodybuf;

    } catch (Exception e) {

      // TODO: proper logging
      System.out.println(e.toString());
      System.out.println(Arrays.toString(e.getStackTrace()));
      return bodybuf;
    }
  }

  private void writeFacetsRecurse(JsonGenerator gen, Facets facets, String dimension, String... path)
      throws IOException {

    var result = facets.getAllChildren(dimension, path);
    if (result == null)
      return;

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
