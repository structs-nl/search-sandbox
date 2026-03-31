package nl.structs;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.HashMap;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.apache.lucene.util.BytesRef;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.uuid.Generators;

import io.netty.buffer.ByteBufOutputStream;
import io.netty.buffer.Unpooled;
import nl.structs.Querier.HighlightsAsObject;
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
import org.apache.lucene.search.DocIdSetIterator;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TopDocs;

import org.apache.lucene.search.uhighlight.PassageFormatter;
import org.apache.lucene.search.uhighlight.Passage;
import org.apache.lucene.facet.FacetsConfig;

import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.uhighlight.UnifiedHighlighter;
import org.apache.lucene.store.FSDirectory;

public class Querier {

	protected ObjectMapper mapper;
	protected FacetsConfig fconfig;
	protected IndexSearcher indexSearcher;
	protected DirectoryReader indexReader;
	protected Analyzer analyzer;
	protected HighlightsAsObject highlighter;
	protected DirectoryTaxonomyReader taxoReader;
	protected HashMap<String, SearchState> searchstates = new HashMap<String, SearchState>();

	public Querier(FSDirectory indexdir, FSDirectory taxdir, ObjectMapper mapper, FacetsConfig fconfig)
			throws IOException, InterruptedException {

		this.mapper = mapper;
		this.fconfig = fconfig;
		indexReader = DirectoryReader.open(indexdir);
		taxoReader = new DirectoryTaxonomyReader(taxdir);
		indexSearcher = new IndexSearcher(indexReader);
		analyzer = new StandardAnalyzer();

		highlighter = new HighlightsAsObject(UnifiedHighlighter.builder(indexSearcher, analyzer)	
								.withMaxLength(1000000000) // is there a better way of doing this?
								.withFormatter(new PassageReturningFormatter()));
	}

	public void close() throws IOException {
		IOUtils.close(indexReader, taxoReader);
	}

	protected class SearchState {
		public Query query;
		public ScoreDoc doc;

		SearchState(Query q, ScoreDoc doc) {
			query = q;
			this.doc = doc;
		}
	}

	class HighlightResult {
		public Passage[] passages;
		public String content;		

		HighlightResult(Passage[] passages, String content) {
			this.passages = passages;
			this.content = content;
		}
	}

	class PassageReturningFormatter extends PassageFormatter {
		public HighlightResult format(Passage[] passages, String content) {

			for (var passage : passages) {
				System.out.print(passage.getStartOffset() + " - " + passage.getEndOffset());
				System.out.println("\tterm matches: " + passage.getNumMatches());

				for (int i = 0; i < passage.getNumMatches(); i++) {
						int start = passage.getMatchStarts()[i];
						int end = passage.getMatchEnds()[i];					
						BytesRef term = passage.getMatchTerms()[i];

						System.out.println(start + "-" + end + "\t" + content.substring(start, end) + "\t" + term.utf8ToString());

					}
			}

			return new HighlightResult(passages, content);
		}
	}

	class HighlightsAsObject extends UnifiedHighlighter {

		public HighlightsAsObject(UnifiedHighlighter.Builder builder) {
			super(builder);
		}

		// Expose the protected method publicly
		public UnifiedHighlighter.OffsetSource offsetSource(String field) {
			return super.getOffsetSource(field);
		}
		
		// Expose the protected method publicly
		public Map<String, Object[]> highlight(
				String[] fields, 
				Query query, 
				ScoreDoc[] scoreDocs, 
				int[] maxPassages) throws IOException {

				// Extract document IDs from ScoreDoc array
				int[] docIds = new int[scoreDocs.length];
				for (int i = 0; i < scoreDocs.length; i++) {
					docIds[i] = scoreDocs[i].doc;
				}

			return this.highlightFieldsAsObjects(fields, query, docIds, maxPassages);
		}
	}

	public static SearchQuery parseQuery(JsonNode json) {
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

		if (!querynode.isMissingNode() && !querynode.isNull() && !querynode.asText().isEmpty()) {
			sq.queryString = querynode.asText();
		}

		var facetpagenode = json.at("/facetpagesize");
		if (!facetpagenode.isMissingNode() && !facetpagenode.isNull() && !facetpagenode.asText().isEmpty()) {

			// TODO errorhandline
			sq.facetPageSize = facetpagenode.asInt();
		}

		for (var filter : json.at("/facetfilters")) {

			// TODO: describe what we are doing here

			var dim = "";
			var path = new LinkedList<String>();

			var elems = filter.elements();
			while (elems.hasNext()) {
				var elem = elems.next();

				if (dim.isEmpty()) {
					dim = elem.asText();
				} else {
					path.add(elem.asText());
				}
			}

			if (!dim.isEmpty() && path.size() > 0) {
				var patharr = new String[path.size()];
				patharr = path.toArray(patharr);
				sq.facetfilters.add(sq.new PathFilter(dim, patharr));
			}
		}

		return sq;
	}

	public static class SearchQuery {

		public String queryid = "";
		public Integer pageSize;
		public String queryString = "";

		public LinkedList<PathFilter> facetfilters = new LinkedList<PathFilter>();

		public class PathFilter {
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
			throws IOException, InterruptedException {

		// Move the JSON parsing here
		
		TopDocs topdocs = null;
		Query currentQuery = null;
		ByteBuf bodybuf = Unpooled.directBuffer(8);

		// TODO Check if the index should be re-opened after a write operation

		try {
			var byteoutput = new ByteBufOutputStream(bodybuf);
			var searchquery = parseQuery(json);

			var gen = mapper.getFactory().createGenerator((OutputStream) byteoutput);
			gen.writeStartObject();

			if (searchquery.queryid.isEmpty() == false) {

				// Continue a stored query
				// TODO: clear the queries

				var searchstate = searchstates.get(searchquery.queryid);
				topdocs = indexSearcher.searchAfter(searchstate.doc, searchstate.query, searchquery.pageSize);
				
				searchstate.doc = topdocs.scoreDocs[topdocs.scoreDocs.length - 1];
				searchstates.put(searchquery.queryid, searchstate);

				gen.writeStringField("qid", searchquery.queryid);
				gen.writeStringField("hits", Long.toString(topdocs.totalHits.value()));

			} else {

				// Create a new query with facets

				var querybuilder = new BooleanQuery.Builder();				
				var standardparser = new StandardQueryParser(analyzer);

				// TODO: Generalize this from the config file: what types to output in the query, if not all?

				querybuilder.add(new TermQuery(new Term("type", "file")), BooleanClause.Occur.FILTER);

				// TODO: specifiy the default field in the config file, and use that here instead of hardcoding "content"

				if (searchquery.queryString.isEmpty() == false) {
					querybuilder.add(standardparser.parse(searchquery.queryString, "content"), BooleanClause.Occur.MUST);
				}

				var query = querybuilder.build();
				var dq = new DrillDownQuery(fconfig, query);

				for (var filter : searchquery.facetfilters) {
					dq.add(filter.dimension, filter.path);
				}

				var result = new DrillSideways(indexSearcher, fconfig, taxoReader).search(dq,
						searchquery.pageSize);

				
				topdocs = result.hits;
				currentQuery = dq;

				if (topdocs.scoreDocs.length == 0) {
					gen.writeNumberField("hits", 0);
				} else {
					// results; store query and gather facets

					var queryuuid = Generators.timeBasedGenerator().generate();
					var searchstate = new SearchState(currentQuery, topdocs.scoreDocs[topdocs.scoreDocs.length - 1]);

					searchstates.put(queryuuid.toString(), searchstate);

					gen.writeStringField("qid", queryuuid.toString());
					gen.writeNumberField("hits", topdocs.totalHits.value());

					gen.writeArrayFieldStart("facets");

					// TODO: this is only one facet. Grab the facet fields from the config and loop over them here

					var parents = result.facets.getAllChildren("parents");

					// TODO: this is a specific kind of hierarchical facet: one where the nodes are part of the index
					// Specify in the config file

					for (var lv : parents.labelValues) {
						gen.writeStartObject();
						gen.writeStringField("field", "parents");
						gen.writeStringField("uuid", lv.label);
						gen.writeNumberField("count", lv.value.intValue());

						// TODO: can this be done faster with a more direct Lucene lookup?
						// TODO: what should be specified in the config? The link field and the label field(s)

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

			if (topdocs.scoreDocs.length > 0) {

				// Document result rendering. This is used for new and continued queries.
				// TODO: move stuff to the config file

				var high = highlighter.highlight(new String[] { "content" }, currentQuery, topdocs.scoreDocs, new int[] { 100 });

				//var contentHight = high.get("content");



				//var source = highlighter.offsetSource("content");
				//System.out.println(source.toString());
				
				gen.writeArrayFieldStart("docs");

				for (var i = 0; i < topdocs.scoreDocs.length; i++) {
					var hit = topdocs.scoreDocs[i];
					var doc = indexSearcher.storedFields().document(hit.doc);					

					var title = doc.get("title");
					var uuid = doc.get("uuid");

					gen.writeString(uuid);

					// Go through the highlights
					// Lookup the terms and payloads!


					//var highlight = highlights.get("content");
					//if (highlight != null ) {


					//}
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
}
