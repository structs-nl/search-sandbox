/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package main.java.nl.structs;

import java.io.IOException;
import java.util.LinkedList;
import java.util.ListIterator;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;

import org.apache.lucene.analysis.TokenFilter;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.analysis.tokenattributes.OffsetAttribute;
import org.apache.lucene.analysis.tokenattributes.PositionIncrementAttribute;
import org.apache.lucene.analysis.tokenattributes.PositionLengthAttribute;
import org.apache.lucene.analysis.tokenattributes.TypeAttribute;
import org.apache.lucene.util.AttributeSource;
import org.apache.lucene.util.CharsRefBuilder;
import org.apache.lucene.util.RollingBuffer;

public final class 
AnnotateFilter extends TokenFilter {

  public static final String TOKEN_TYPE = "ANNOTATION";

  private final CharTermAttribute termAtt = addAttribute(CharTermAttribute.class);
  private final PositionIncrementAttribute posIncrAtt = addAttribute(PositionIncrementAttribute.class);
  private final PositionLengthAttribute posLenAtt = addAttribute(PositionLengthAttribute.class);
  private final TypeAttribute typeAtt = addAttribute(TypeAttribute.class);
  private final OffsetAttribute offsetAtt = addAttribute(OffsetAttribute.class);

  private final LinkedList<BufferedOutputToken> outputBuffer = new LinkedList<>();

  private int maxLookaheadUsed;

  private boolean liveToken;

  // True once the input TokenStream is exhausted:
  private boolean finished;

  private int lookaheadNextRead;
  private int lookaheadNextWrite;

  public static class Annotation {
    public int startOffset;
    public int endOffset;
    public String annotation;

    public Annotation(int startOffset, int endOffset, String annotation) {
      this.startOffset = startOffset;
      this.endOffset = endOffset;
      this.annotation = annotation;
    }
  }

  private final LinkedList<Annotation> annotations;
  private ListIterator<Annotation> annotationIterator;
  private Annotation currentAnnotation;

  private RollingBuffer<BufferedInputToken> lookahead = new RollingBuffer<BufferedInputToken>() {
    @Override
    protected BufferedInputToken newInstance() {
      return new BufferedInputToken();
    }
  };

  static class BufferedInputToken implements RollingBuffer.Resettable {
    final CharsRefBuilder term = new CharsRefBuilder();
    AttributeSource.State state;
    int startOffset = -1;
    int endOffset = -1;

    @Override
    public void reset() {
      state = null;
      term.clear();

      // Intentionally invalid to ferret out bugs:
      startOffset = -1;
      endOffset = -1;
    }
  }

  public class BufferedOutputToken {

    public State state;
    public String term;
    public int startPos;
    public int endPos;
    public int startOffset;
    public int endOffset;
    public int posLength;
    public int posIncrement;
    public boolean isPartial;

    public BufferedOutputToken(State state) {

      // constructor for original input tokens

      this.state = state;
      this.term = null;
      this.startPos = 0;
      this.endPos = 0;
      this.startOffset = 0;
      this.endOffset = 0;
      this.posIncrement = 0;
      this.isPartial = false;
    }

    public BufferedOutputToken( String term, int startPos, int endPos, int startOffset, int endOffset, int posIncrement, boolean isPartial) {
    
      // constructor for anntation tokens

      this.state = null;
      this.term = term;
      this.startPos = startPos;
      this.endPos = endPos;
      this.startOffset = startOffset;
      this.endOffset = endOffset;
      this.posIncrement = posIncrement;
      this.isPartial = isPartial;
    }
  };

  public AnnotateFilter(TokenStream input,  LinkedList<Annotation> annotations) {
    super(input);

    this.annotations = annotations;
    this.annotationIterator = this.annotations.listIterator();

    if (this.annotationIterator.hasNext()) {
      this.currentAnnotation = this.annotationIterator.next();
    }

    // TODO We assume there are annotations. If there is no current token; shortcut the incrementToken
    // We assume the annotationlist is ordered on the start offset
    // TODO Add a sort option

  }

  @Override
  public boolean incrementToken() throws IOException {

    if (outputBuffer.isEmpty() == false) {
      // We still have pending outputs from a prior synonym match:
      releaseBufferedToken();
      // System.out.println(" syn: ret buffered=" + this);
      assert liveToken == false;
      return true;
    }

    // Try to parse a new match at the current token:

    if (parse()) {
      // A new match was found:
      releaseBufferedToken();
      // System.out.println(" syn: after parse, ret buffered=" + this);
      assert liveToken == false;
      return true;
    }

    if (lookaheadNextRead == lookaheadNextWrite) {

      // Fast path: parse pulled one token, but it didn't match
      // the start for any annotations, so we now return it "live" w/o having
      // cloned all of its atts:
      //System.out.println(" fast path: return live token");
      if (finished) {
        // System.out.println(" syn: ret END");
        return false;
      }

      assert liveToken;
      liveToken = false;

      // NOTE: no need to change posInc since it's relative, i.e. whatever
      // node our output is upto will just increase by the incoming posInc.
      // We also don't need to change posLen, but only because we cannot
      // consume a graph, so the incoming token can never span a future
      // synonym match.

    } else {
      // We still have buffered lookahead tokens from a previous
      // parse attempt that required lookahead; just replay them now:
      // System.out.println(" restore buffer");
      assert lookaheadNextRead < lookaheadNextWrite
          : "read=" + lookaheadNextRead + " write=" + lookaheadNextWrite;
      BufferedInputToken token = lookahead.get(lookaheadNextRead);
      lookaheadNextRead++;

      restoreState(token.state);

      lookahead.freeBefore(lookaheadNextRead);

      // System.out.println(" after restore offset=" + offsetAtt.startOffset() + "-" +
      // offsetAtt.endOffset());
      assert liveToken == false;
    }

    return true;
  }

  private void releaseBufferedToken() throws IOException {

    // We moved some of the state in this function to the output token
    // in order to support multiple matches
    // pos length, pos increment, start offset and end offset

    BufferedOutputToken token = outputBuffer.pollFirst();

    if (token.state != null) {
      // This is an original input token (keepOrig=true case):
      restoreState(token.state);
      // System.out.println(" release token " + termAtt.toString());

      // System.out.println(" startOffset=" + offsetAtt.startOffset() + " endOffset="
      // +
      // offsetAtt.endOffset());
    } else {
      clearAttributes();
      termAtt.append(token.term);
      offsetAtt.setOffset(token.startOffset, token.endOffset);
      // System.out.println(" startOffset=" + matchStartOffset + " endOffset=" +
      // matchEndOffset);
      typeAtt.setType(TOKEN_TYPE);
      posIncrAtt.setPositionIncrement(token.posIncrement);
      posLenAtt.setPositionLength(token.endPos - token.startPos); // TODO check
      // System.out.println(" release token " + token.term + " posIncr=" + token.posIncrement + " posLen=" + (token.endPos - token.startPos));
    }
  }

  /**
   * Scans the next input token(s) to see if a synonym matches. Returns true if a
   * match was found.
   */
  private boolean parse() throws IOException {
    // System.out.println(Thread.currentThread().getName() + ": S: parse: " +
    // System.identityHashCode(this));

    LinkedList<BufferedOutputToken> matches = new LinkedList<>();

    // How many tokens in the current match
    int matchLength = 0;
    boolean doFinalCapture = false;
    boolean preTokenMatch = false; // a flag that indicates a match that starts between tokens

    int lookaheadUpto = lookaheadNextRead;

    while (true) {
      // System.out.println(" cycle lookaheadUpto=" + lookaheadUpto + " maxPos=" +
      // lookahead.getMaxPos());

      // Pull next token's chars:
      // String termText;
      //final int bufferLen;
      final int inputEndOffset;
      final int inputStartOffset;

      if (lookaheadUpto <= lookahead.getMaxPos()) {
        // Still in our lookahead buffer
        BufferedInputToken token = lookahead.get(lookaheadUpto);
        lookaheadUpto++;
        //termText = token.term.toString();
        //bufferLen = token.term.length();
        inputEndOffset = token.endOffset;
        inputStartOffset = token.startOffset;

        // System.out.println(" use buffer now max=" + lookahead.getMaxPos());
        
      } else {

        // We used up our lookahead buffer of input tokens
        // -- pull next real input token:

        assert finished || liveToken == false;

        if (finished) {
          // System.out.println(" break: finished");
          break;
        } else if (input.incrementToken()) {
          // System.out.println(" input.incrToken");
          liveToken = true;
          //termText = termAtt.toString();
          //bufferLen = termAtt.length();
          inputStartOffset = offsetAtt.startOffset();
          inputEndOffset = offsetAtt.endOffset();
          lookaheadUpto++;
        } else {
          // No more input tokens
          finished = true;
          // System.out.println(" break: now set finished");
          break;
        }
      }

      // System.out.println(termText);

      matchLength++;

      // System.out.println(" cycle term=" + new String(buffer, 0, bufferLen));

      // Check if there is a match after reading the token      

      // There is a currentAnnotation set in the constructor of AnnotateFilter
      // If this is null, the list is exhausted

      if (currentAnnotation != null) {
        do {
          if (currentAnnotation.startOffset <= inputEndOffset) {
            // the annotation starts before the end of the token
            // TODO is this check sufficient?

            // Add new match to the partialMatches list with:
            // - posIncrement 0. We want to be able to add more than one annotation starting here. the increment is left to the original token
            //   TODO Check this behaviour!
            
            // - startOffset and endOffset are used from the annotation. They are pointers into the original text.
            // - endpos is now set to 0, but we dont know if this is the final value. That's why it's partial.

            var posIncrement = 0;
            var startPos = matchLength - 1;
            var endPos = 0;

            // check if the annotation starts before the start of the token
            // This implies that the token should not be outputted before the annotation in the resulting TokenStream

            // TODO: it is very well possible that a consecutive match also is a pretoken match. is this handled correctly?

            if (matches.isEmpty() && currentAnnotation.startOffset < inputStartOffset) {
              // the annotation starts before the start of the token. It has a posincrement
              preTokenMatch = true;
              posIncrement = 1;
            }

            matches.add(
              new BufferedOutputToken(currentAnnotation.annotation, startPos,endPos, currentAnnotation.startOffset, currentAnnotation.endOffset,posIncrement, true)
            );

            // set the next annotation. For this token or the next

            if (annotationIterator.hasNext()) {
              currentAnnotation = annotationIterator.next();
            } else {
              currentAnnotation = null;
            }
          }
          
        // stop if there are no more annotations or if the start of the new current annotation is beyond the end of current token
        } while (currentAnnotation != null && currentAnnotation.startOffset <= inputEndOffset);
      }

      // Check the partialMatches if the current token is the end of any of them
      // If so, add the matchLength of the partial match
      // If the there are no partialMatches: all matching is done at the 
      // current input position. break the while loop

      boolean doneMatching = true;

      for (BufferedOutputToken token : matches) {
        if (token.isPartial && token.endOffset <= inputEndOffset) {
          token.endPos = matchLength;
          token.isPartial = false;
        }
        if (token.isPartial) {
          doneMatching = false;
        }
      }

      if (doneMatching) {
        // All partial matches are fulfilled. We are done
        // searching for matching rules starting at the
        // current input position.
        break;

      } else {

        // More matching is possible
        doFinalCapture = true;
        if (liveToken) {
          capture();
        }
      }
    }

    if (doFinalCapture && liveToken && finished == false) {
      // Must capture the final token if we captured any prior tokens:
      capture();
    }

    // 4: are there matches (all should be full matches at this point)

    if (matches.size() > 0) {
      
      if (liveToken) {
        // Single input token synonym; we must buffer it now:
        capture();
      }

      bufferOutputTokens(matches, matchLength, preTokenMatch);

      return true;
    } else {
      // System.out.println(" no match; lookaheadNextRead=" + lookaheadNextRead);
      return false;
    }
  }

  /**
   * Expands the output graph into the necessary tokens, adding synonyms as side
   * paths parallel to
   * the input tokens, and buffers them in the output token buffer.
   */
  private void bufferOutputTokens(LinkedList<BufferedOutputToken> matches, int matchLength, boolean pretokenMatch) {

    // We have a list of matches and the tokens that where needed for these matches.
    // We know there is a start of a match at the current position

    // Group the matches by their start position
    SortedSet<Integer> uniqueStartPositions = new TreeSet<Integer>();
    for(BufferedOutputToken token : matches)
      uniqueStartPositions.add(Integer.valueOf(token.startPos));

    // First, output the token that started the match
    // do not output the token if this is a pre-token match, starting between tokens

    if (! pretokenMatch) {
      outputBuffer.add(new BufferedOutputToken(lookahead.get(lookaheadNextRead).state)); 
      lookaheadNextRead++;
    }

    // then, iterate the grouped startpositions
    var posIterator  = uniqueStartPositions.iterator();
    Integer previousPosition = -1;

    while (posIterator.hasNext()) {
      var startPos = posIterator.next();

      // first, fill the gap with the previous match position with original tokens
      if (previousPosition > -1) {

        int gap = startPos - previousPosition;
        for (int j = 0; j < gap; j++) {
          outputBuffer.add(new BufferedOutputToken(lookahead.get(lookaheadNextRead).state)); 
          lookaheadNextRead++;
        }
      }

      // then, output all matches starting at this position
      for(BufferedOutputToken match : matches) {
        if (Integer.valueOf(match.startPos) == startPos) {
          outputBuffer.add(match);
        }
      }

      if (posIterator.hasNext()) {
        // remember the current position as the previous one for the next iteration
        previousPosition = startPos;

      } else {

        // last match: fill until the end of the match with original tokens
        // TODO check!!
      
        int gap = (matchLength - 1) - startPos;

        for (int j = 0; j < gap; j++) {
          outputBuffer.add(new BufferedOutputToken(lookahead.get(lookaheadNextRead).state)); 
          lookaheadNextRead++;
        }
      }
    }

    // System.out.println(" precmatch; set lookaheadNextRead=" + lookaheadNextRead +
    // " now max="
    // + lookahead.getMaxPos());
    lookahead.freeBefore(lookaheadNextRead);
    // System.out.println(" match; set lookaheadNextRead=" + lookaheadNextRead + "
    // now max=" +
    // lookahead.getMaxPos());
  }

  /** Buffers the current input token into lookahead buffer. */
  private void capture() {
    assert liveToken;
    liveToken = false;
    BufferedInputToken token = lookahead.get(lookaheadNextWrite);
    lookaheadNextWrite++;

    token.state = captureState();
    token.startOffset = offsetAtt.startOffset();
    token.endOffset = offsetAtt.endOffset();
    assert token.term.length() == 0;
    token.term.append(termAtt);

    maxLookaheadUsed = Math.max(maxLookaheadUsed, lookahead.getBufferSize());
    // System.out.println(" maxLookaheadUsed=" + maxLookaheadUsed);
  }

  @Override
  public void reset() throws IOException {
    super.reset();
    lookahead.reset();
    lookaheadNextWrite = 0;
    lookaheadNextRead = 0;
    finished = false;
    liveToken = false;
    outputBuffer.clear();
    maxLookaheadUsed = 0;
    // System.out.println("S: reset");
  }
}