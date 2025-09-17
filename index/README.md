
# Technical notes


https://lucene.apache.org/core/10_0_0/demo/org/apache/lucene/demo/facet/package-summary.html

https://github.com/jiepujiang/LuceneTutorial/blob/master/README.md

java -jar ./target/Searcher-0.8.jar -path ./data -index ../transform/output.json

java -jar ./target/Searcher-0.8.jar -path ./data -serve 8080

curl -d '{"pagesize": 1000,"facetpagesize": 100, "query": "", "facetfilters": []}' -H "Content-Type: application/json" -X PUT http://localhost:8080/query | jq .


curl -d '{"pagesize": 100,"facetpagesize": 100, "query": "title:de", "facetfilters": [["parents", "8b238f43-de8e-11bf-e053-09f0900a4541"]]}' -H "Content-Type: application/json" -X PUT http://localhost:8080/query | jq .
	
**Docker create**

docker build -t search-sandbox-dev-env .
docker run --name search-sandbox -p 8080:8080 -it -v "./:/app" search-sandbox-dev-env
	
