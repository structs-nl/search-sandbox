package nl.structs;

import java.io.File;
import java.io.IOException;
import java.util.HashSet;

import com.fasterxml.jackson.core.JsonProcessingException;

import com.fasterxml.jackson.databind.MappingIterator;
import com.fasterxml.jackson.dataformat.csv.CsvMapper;
import com.fasterxml.jackson.dataformat.csv.CsvSchema;

import org.apache.lucene.util.BytesRef;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.search.suggest.analyzing.AnalyzingInfixSuggester;
import org.apache.lucene.store.FSDirectory;

public class Suggester {

    protected Analyzer analyzer;
    protected AnalyzingInfixSuggester infixSuggester;

    public static class Value  {

        public Value () {

        }
        public String key, value, context;
    }

    public Suggester(FSDirectory suggestdir)
    throws IOException, InterruptedException  {
        this.analyzer = new StandardAnalyzer();
        this.infixSuggester = new AnalyzingInfixSuggester(suggestdir, analyzer);

    }

    public void ingest(String filename)
    throws IOException {

        var file = new File(filename);
    
        var mapper = new CsvMapper();

        var schema =  CsvSchema.builder()
            .setColumnSeparator(';')
            .setUseHeader(true)
            .addColumn("key")
            .addColumn("context")
            .addColumn("value")
            .build();

        MappingIterator<Value> it = mapper
            .readerFor(Value.class)
            .with(schema)
            .readValues(file);

        var indexedCount = 0;

        while (it.hasNextValue()) {
            Value val = it.nextValue();

            if (val == null) {
                continue;
            }

            if (isBlank(val.key) || isBlank(val.value) || isBlank(val.context)) {
                continue;
            }

            var fieldsSet = new HashSet<BytesRef>();
            fieldsSet.add(new BytesRef(val.context.trim()));

            this.infixSuggester.add(new BytesRef(val.key.trim()), fieldsSet, 0, new BytesRef(val.value.trim()));
            indexedCount++;
        }

        if (indexedCount > 0) {
            this.infixSuggester.commit();
        }

    }

    private static boolean isBlank(String value) {
        return value == null || value.isEmpty();
    }

    public void suggest(String infix, String context)
        throws IOException, JsonProcessingException {

        // this.infixSuggester.lookup(infix, 

    }

    public void close() throws IOException {
        infixSuggester.close();
    }

}