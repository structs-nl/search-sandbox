# Doel
	
In een sandbox omgeving testen van een aantal centrale search features voor het GLOBALISE onderzoeksportaal.

De focus zal in eerste instantie liggen op hierarchische facetten, welke voor twee doelen worden gebruikt:

- Een tree component voor het filteren op / browsen door  verschillende "referentie data" bronnen
- Een autocomplete in het searchveld voor "referentie data" bronnen

In de sandbox hebben we nog geen beschikking over de definitieve data,
dus moeten we werken met een subset die wel beschikbaar is en helpt bij de functionele vragen.
	
# Werk te doen


## Data verzamelen en verwerken

- [x] EAD parsen en json produceren
- [] Inception materiaal parsen en json produceren voor NER, events en entities
	
## Elastic

In ZIT is op basis van ES een hierarchisch filter gebouwd. Hier zijn de paden toegevoegd aan de docs,
welke in een standaard term aggregate komen. Deze wordt vanuit de UI bevraagd met regexes. De huidige
implementatie was "buiten budget" en niet generiek opgezet. De indexeringsstructuur en
client server interactie moet goed beschreven worden.

https://github.com/structs-nl/zoekintranscripties

- [] index structuur beschrijven
- [] Data indexeren in docker instance
- [] queries beschrijven

## Lucene

https://lucene.apache.org/core/10_0_0/demo/org/apache/lucene/demo/facet/package-summary.html

https://github.com/jiepujiang/LuceneTutorial/blob/master/README.md

java -jar ./target/Searcher-0.8.jar -path ./data -index ../transform/output.json
java -jar ./target/Searcher-0.8.jar -path ./data -serve 8080

curl -d '{"pagesize": 100,"facetpagesize": 100, "query": "title:de", "facetfilters": [["parents", "8b238f43-de8e-11bf-e053-09f0900a4541"]]}' -H "Content-Type: application/json" -X PUT http://localhost:8080/query | jq .
	
**Docker create**

docker build -t search-sandbox-dev-env .
docker run --name search-sandbox -p 8080:8080 -it -v "./:/app" search-sandbox-dev-env

## UI

- [] simpele ui met searchbox, resultatenlijst en https://infinite-tree.js.org sectie
- [] API implementeren


	
