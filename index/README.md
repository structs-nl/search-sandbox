
# Technical notes

https://github.com/jiepujiang/LuceneTutorial/blob/master/README.md

java -jar ./target/Searcher-0.8.jar -path ./data -index ../transform/output.json
java -jar ./target/Searcher-0.8.jar -path ./data -serve 8080
	
**Docker create**

docker build -t search-sandbox-dev-env .
docker run --name search-sandbox -p 8080:8080 -it -v "./:/app" search-sandbox-dev-env

