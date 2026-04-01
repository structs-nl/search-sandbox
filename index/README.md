OpenAPI specification
- ingest
- query

queries:
TermIntervalsSource.java
Used by Intervals.java

intervals.java: what queries are possible with terms? regexps, ranges

Missing in the parser: regexp queries
git checkout releases/lucene/10.2.2
https://github.com/apache/lucene-solr/pull/772/


# Technical notes

https://github.com/jiepujiang/LuceneTutorial/blob/master/README.md
https://lucene.apache.org/core/10_0_0/queryparser/org/apache/lucene/queryparser/flexible/standard/nodes/intervalfn/package-summary.html



java -jar ./target/Enlight-0.2.jar -path ./data -index ../transform/output.json
java -jar ./target/Enlight-0.2.jar -path ./data -serve 8080

mvn exec:java -Dexec.mainClass="nl.structs.Enlight" -Dexec.args="-path ./data -serve 8080"
mvn exec:java -Dexec.mainClass="nl.structs.TokenizeTest"

mvn exec:java -Dexec.mainClass="nl.structs.Enlight" -Dexec.args="-path ./data -index ./index_test.json"
mvn exec:java -Dexec.mainClass="nl.structs.Enlight" -Dexec.args="-path ./data -index ../transform/output.json"

**Docker create**

docker build -t search-sandbox-dev-env .
docker run --name search-sandbox -p 8080:8080 -it -v "./:/app" search-sandbox-dev-env