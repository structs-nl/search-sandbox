**A Lucene TokenFilter that adds annotations**

As Mike Mccandles describes in his <a href="https://blog.mikemccandless.com/2012/04/lucenes-tokenstreams-are-actually.html">blog</a>: Lucene TokenStreams are actually graphs!

Inspired by this, we present AnnotateFilter, a TokenFilter that makes it possible to add annotation to a TokenStream. By annotations we mean terms that have a start and end offset in the text that was the source of the TokenStream.

For instance, if have "concept1" at characters 5 to 9 in the original text, we can add the term "concept1" in the TokenStream. The term is added to the normally linear TokenStream, making it a graph. A directed graph actually. Because "concept1" is simply a term, we can search for it just as we search for other terms.

The following TokenStream consists of the text "This is a test of the tokenization process" and annotations:

- from: 5 to: 9, term: "concept1"
- from: 10, to: 14, term: "concept3"
- from: 10, to: 42, term: "concept2"
- from: 18, to: 21, term: "concept32"
- from: 18, to: 34, term: "concept5"
- from: 35, to: 42, term: "concept8"

<img src="./graph.svg">

The annotations carry the from and to offset in the original text. The only thing the AnnotateFilter does is matching these ofsets with a given TokenStream: given a list of annotations and a TokenStream, the annotations are matched and added to the TokenStream.

## How does the filter work?

The TokenFilter is an iterator: a token is pulled by an upstream process and the TokenFilter pulls a token from a downstream process. This makes a lookahead more cumbersome. We started with the code of a TokenFilter that also does matching with lookaheads: Lucene's SynonymGraphFilter. We kept the lookahead structure of this filter, with several buffers, removed the synonym FST matching logic and added our own matching logic. This is in a sense more easy: synonyms can consist of multiple terms, annotations only consist of a single term. It is also more complicated: different annotations can start at the same term and an annotation can span beyond the start of another annotation.

- The start the annotation must be between the start- and end offset of a term
- The end of the annotation must be between the start- and end offset of a subsequent term
- The match is added behind the starting token, with a "positionincrement" of 0 and a "positionlength" of the number of tokens of the match

## How can it be used?

- Highlights

- PositionLength encoded in the payload and decoded in a query module


## Further work



## test command

mvn clean compile exec:java -Dexec.mainClass="main.java.nl.structs.TokenizeTest"
