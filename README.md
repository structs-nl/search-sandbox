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
- [ ] Inception parsen en json produceren voor NER, events en entities
	
## Elastic

In ZIT is op basis van ES een hierarchisch filter gebouwd. Hier zijn de paden toegevoegd aan de docs,
welke in een standaard term aggregate komen. Deze wordt vanuit de UI bevraagd met regexes. De huidige
implementatie was "buiten budget" en niet generiek opgezet. De indexeringsstructuur en
client server interactie moet goed beschreven worden.

https://github.com/structs-nl/zoekintranscripties

- [ ] index structuur beschrijven
- [ ] Data indexeren in docker instance
- [ ] queries beschrijven

## Lucene

Lucene heeft een native hierarchische facet implementatie (ja, zelfs twee) die niet in Elastic beschikbaar is.
Elastic heeft haar eigen 'aggregates' implementatie.

Het is eenvoudig om Lucene beschikbaar te stellen met een http server en de Java API direct te gebruiken ipv de JSON wrappers van Elastic. Een alternatieve benadering voor de logge Elastic servers die een middleware laag (en dus extra werk) voorkomt.

https://lucene.apache.org/core/10_0_0/demo/org/apache/lucene/demo/facet/package-summary.html


- [x] json indexeren
- [x] hierarchische drill down queries
- [ ] meerdere waarden per dimensie
- [ ] SortedSet implementatie testen
- [ ] zoeken naar facetten
- [ ] beschrijven van de structuur
- [ ] indexeren via http ipv file
- [ ] nested documents


## UI

- [ ] simpele ui met searchbox, resultatenlijst en https://infinite-tree.js.org sectie
- [ ] API implementeren


	
