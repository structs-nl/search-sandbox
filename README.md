# Enlight: lightweight Lucene server

*work-in-progress!*

Here you can find a rough draft of a lightweight Lucene based search server.
It is easy to work with, with very little configuration. For more advanced use-cases, the very small codebase can be tailored.

Elastic and Solr expose a great deal of the Lucene Java API via REST. Enlight does not do that. It simply uses the Lucene Java API and allows to tailor the REST API for your specific needs.

It does not support replication, as there is no need for it at this moment. For static indices, multiple nodes can provide replication by copying the index. For near realtime replication, the segment based replication module of Lucene can be used. This is developed for use-cases where the document-based replication won't do it.

## A short description of the code

- **Enlight.java** is the main class that starts a webserver and does the request handling. Nothing fancy going on here

- **Indexer.java** contains lots of test indexing code that will be removed. The Indexer will merely process the documents sent to the REST API.

- **Querier.java** contains most of the action. There are several utility classes for parsing the sent query (SearchQuery), remembring queries in memory for the continuation (SearchState). Most of the lines-of-code are the JSON response generation. There is a Lucene query constructed from the sent query and the Lucene Facet module is used. This is standard stuff. The "nodes" in the hierarchical facets are stored as documents and retrieved when the facets are rendered. We will describe this in more detail later on.

- **HighlightsAsObjects.java** is an extension of the UnifiedHighlighter with only one task: exposing the protected method HighlightsAsObjects. This allows for custom highlighting. This is done in **HighlightsFormatter.java**, which merely returns some details of the highlighting that we want to use in the response. It's mainly the start and end offsets in the original text, plus the matching term that we are interested in.

## AnnotateFilter

The https://github.com/structs-nl/AnnotateFilter class is a submodule that allows the addition of annotations to the index. These annotations can be searched for and can highlighted in our custom highlighter.

## Interval query using the position length

The annotations have a positionlength that is not stored in the index. Via another filter, the positionlenght can be stored in the payload in the index and then retrieved in the query process. We have done an experiment with a patched interval query module that uses this length-as-payload information and the results are highly encouraging. The Enlight project uses a custom query module that we have built locally. We will will expand on this later on.

The patched code can be found in the following branch of Lucene 10.2.2: https://github.com/structs-nl/lucene/tree/PosLenQuery-10.2.2. Only the lucene-queries and lucene-queryparser jar's are needed. Lucene queries contains the changes (lucene/queries/src/java/org/apache/lucene/queries/intervals/TermIntervalsSource.java). Queries uses this patched file in the interval queries.


# Configuring and running stuff




# Getting stuff in

TODO install

python3 ingest/ingest.py

# Building stuff

Make sure java 21 is present

git clone https://github.com/structs-nl/lucene

cd lucene

git checkout PosLenQuery-10.2.2

./gradlew mavenToLocal

cd ..

git clone https://github.com/structs-nl/enlight

cd enlight

mvn compile package

java -Xmx8g -jar target/enlight-0.1-SNAPSHOT.jar -path ./data -port 8080 > enlight.log 2>&1

# Publishing stuff

echo $CR_PAT | docker login ghcr.io -u rgoene --password-stdin
docker build --tag ghcr.io/structs-nl/enlight:latest --push .

mvn -Drepo.id=github -Drepo.login=rgoene -Drepo.pwd=$CR_PAT deploy