# Doel

In een sandbox omgeving testen van een aantal centrale search features voor het GLOBALISE onderzoeksportaal.

De focus zal in eerste instantie liggen op hierarchische facetten, welke voor twee doelen worden gebruikt:

- Een tree component voor het filteren op / browsen door  verschillende "referentie data" bronnen
- Een autocomplete in het searchveld voor "referentie data" bronnen

In de sandbox hebben we nog geen beschikking over de definitieve data,
dus moeten we werken met een subset die wel beschikbaar is en helpt bij de functionele vragen.

# Werk te doen
## Opzet ZIT beschrijven

In ZIT is op basis van ES een hierarchisch filter gebouwd. Hier zijn de paden toegevoegd aan de docs,
welke in een standaard term aggregate komen. Deze wordt vanuit de UI bevraagd met regexes. De huidige
implementatie was "buiten budget" en niet generiek opgezet. De indexeringsstructuur en
client server interactie moet goed beschreven worden.

https://github.com/structs-nl/zoekintranscripties

## Data verzamelen en verwerken

EAD en Inception materiaal met NER, events en "entiteiten"
node script dat json produceert.

## Elastic
	
Direct met ES praten vanuit de UI

term aggregate queries with regex

docker pull docker.elastic.co/elasticsearch/elasticsearch:8.16.1

docker run --name es-hier -p 9200:9200 -it -m 1GB docker.elastic.co/elasticsearch/elasticsearch:8.16.1

HTTP CA certificate SHA-256 fingerprint:
0fbdf53d102ddb4c406fb2b6a7d3645f89db522c55090cf05e25eec2b2a20bdb

curl -k -u elastic:R85keXC1+IFBIwu1oiJw https://localhost:9200/_cat/nodes

## Lucene


https://lucene.apache.org/core/4_1_0/facet/org/apache/lucene/facet/doc-files/userguide.html






docker start -i search-sandbox
mkdir ./opt/data
cd app/index
mvn package
java -jar ./target/jPointerStore-1.0-SNAPSHOT.jar -path ../:../opt/data -index ../transform/output.json








**Docker create**

docker build -t search-sandbox-dev-env .
docker run --name search-sandbox -it -v "./:/app" search-sandbox-dev-env


### IndexerPointerStore

Index manager
FacetIndex



addIndexField

/Collection
/StartDate
/Description
/Documents/Text
/Documents/Title


IndexFile (uit de indexer)
 - new document
 - index: iterate: loop door de IndexFields
 
 - hoe ziet de index eruit?
 - documenten direct maken

 - hierarchische facetten
 - nested documents
 
 
 
 
### FilePointerStore

### PointerServer


## UI

https://infinite-tree.js.org
voor zowel facetten als de documenten
	
