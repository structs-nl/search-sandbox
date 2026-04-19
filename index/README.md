
intervals.java: what queries are possible with terms? regexps, ranges

Missing in the parser: regexp queries
git checkout releases/lucene/10.2.2
https://github.com/apache/lucene-solr/pull/772/

# Technical notes

https://github.com/jiepujiang/LuceneTutorial/blob/master/README.md
https://lucene.apache.org/core/10_0_0/queryparser/org/apache/lucene/queryparser/flexible/standard/nodes/intervalfn/package-summary.html


curl -X PUT "localhost:8080/ingest" -F "file=@index_test.json" 

mvn clean compile exec:java -Dexec.mainClass="nl.structs.Enlight" -Dexec.args="-path ./data -port 8080"

java -jar ./target/Enlight-0.2.jar -path ./data -port 8080

**Docker create**

mvn clean package

docker build -t enlight .
docker run --name enlight -p 8080:8080 -it -v "./:/app" enlight