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

package org.apache.lucene.util;

import java.io.IOException;
import java.util.Arrays;

import org.apache.lucene.search.DocIdSet;
import org.apache.lucene.search.DocIdSetIterator;

/**
 * BitSet of fixed length (numBits), backed by accessible ({@link #getBits})
 * long[], accessed with an int index, implementing {@link Bits} and
 * {@link DocIdSet}. If you need to manage more than 2.1B bits, use
 * {@link LongBitSet}.
 * 
 * @lucene.internal
 */
public final class FixedBitSet extends BitSet implements MutableBits, Accountable {

  private static final long BASE_RAM_BYTES_USED = RamUsageEstimator.shallowSizeOfInstance(FixedBitSet.class);

  /**
   * If the given {@link FixedBitSet} is large enough to hold {@code numBits},
   * returns the given bits, otherwise returns a new {@link FixedBitSet} which
   * can hold the requested number of bits.
   * 
   * <p>
   * <b>NOTE:</b> the returned bitset reuses the underlying {@code long[]} of
   * the given {@code bits} if possible. Also, calling {@link #length()} on the
   * returned bits may return a value greater than {@code numBits}.
   */
  public static FixedBitSet ensureCapacity(FixedBitSet bits, int numBits) {
    if (numBits < bits.length()) {
      return bits;
    } else {
      int numWords = bits2words(numBits);
      long[] arr = bits.getBits();
      if (numWords >= arr.length) {
        arr = ArrayUtil.grow(arr, numWords + 1);
      }
      return new FixedBitSet(arr, arr.length << 6);
    }
  }

  /** returns the number of 64 bit words it would take to hold numBits */
  public static int bits2words(int numBits) {
    int numLong = numBits >>> 6;
    if ((numBits & 63) != 0) {
      numLong++;
    }
    return numLong;
  }

  /**
   * Returns the popcount or cardinality of the intersection of the two sets.
   * Neither set is modified.
   */
  public static long intersectionCount(FixedBitSet a, FixedBitSet b) {
    return BitUtil.pop_intersect(a.bits, b.bits, 0, Math.min(a.numWords, b.numWords));
  }

  /**
   * Returns the popcount or cardinality of the union of the two sets. Neither
   * set is modified.
   */
  public static long unionCount(FixedBitSet a, FixedBitSet b) {
    long tot = BitUtil.pop_union(a.bits, b.bits, 0, Math.min(a.numWords, b.numWords));
    if (a.numWords < b.numWords) {
      tot += BitUtil.pop_array(b.bits, a.numWords, b.numWords - a.numWords);
    } else if (a.numWords > b.numWords) {
      tot += BitUtil.pop_array(a.bits, b.numWords, a.numWords - b.numWords);
    }
    return tot;
  }

  /**
   * Returns the popcount or cardinality of "a and not b" or
   * "intersection(a, not(b))". Neither set is modified.
   */
  public static long andNotCount(FixedBitSet a, FixedBitSet b) {
    long tot = BitUtil.pop_andnot(a.bits, b.bits, 0, Math.min(a.numWords, b.numWords));
    if (a.numWords > b.numWords) {
      tot += BitUtil.pop_array(a.bits, b.numWords, a.numWords - b.numWords);
    }
    return tot;
  }

  final long[] bits;
  final int numBits;
  final int numWords;

  public FixedBitSet(int numBits) {
    this.numBits = numBits;
    bits = new long[bits2words(numBits)];
    numWords = bits.length;
  }

  public FixedBitSet(long[] storedBits, int numBits) {
    this.numWords = bits2words(numBits);
    if (numWords > storedBits.length) {
      throw new IllegalArgumentException("The given long array is too small  to hold " + numBits + " bits");
    }
    this.numBits = numBits;
    this.bits = storedBits;
  }

  @Override
  public int length() {
    return numBits;
  }

  @Override
  public long ramBytesUsed() {
    return BASE_RAM_BYTES_USED + RamUsageEstimator.sizeOf(bits);
  }

  /** Expert. */
  public long[] getBits() {
    return bits;
  }

  @Override
  public int cardinality() {
    return (int) BitUtil.pop_array(bits, 0, bits.length);
  }

  @Override
  public boolean get(int index) {
    assert index >= 0 && index < numBits: "index=" + index + ", numBits=" + numBits;
    int i = index >> 6;               // div 64
    // signed shift will keep a negative index and force an
    // array-index-out-of-bounds-exception, removing the need for an explicit check.
    long bitmask = 1L << index;
    return (bits[i] & bitmask) != 0;
  }

  public void set(int index) {
    assert index >= 0 && index < numBits: "index=" + index + ", numBits=" + numBits;
    int wordNum = index >> 6;      // div 64
    long bitmask = 1L << index;
    bits[wordNum] |= bitmask;
  }

  public boolean getAndSet(int index) {
    assert index >= 0 && index < numBits;
    int wordNum = index >> 6;      // div 64
    long bitmask = 1L << index;
    boolean val = (bits[wordNum] & bitmask) != 0;
    bits[wordNum] |= bitmask;
    return val;
  }

  @Override
  public void clear(int index) {
    assert index >= 0 && index < numBits;
    int wordNum = index >> 6;
    long bitmask = 1L << index;
    bits[wordNum] &= ~bitmask;
  }

  public boolean getAndClear(int index) {
    assert index >= 0 && index < numBits;
    int wordNum = index >> 6;      // div 64
    long bitmask = 1L << index;
    boolean val = (bits[wordNum] & bitmask) != 0;
    bits[wordNum] &= ~bitmask;
    return val;
  }

  @Override
  public int nextSetBit(int index) {
    assert index >= 0 && index < numBits : "index=" + index + ", numBits=" + numBits;
    int i = index >> 6;
    long word = bits[i] >> index;  // skip all the bits to the right of index

    if (word!=0) {
      return index + Long.numberOfTrailingZeros(word);
    }

    while(++i < numWords) {
      word = bits[i];
      if (word != 0) {
        return (i<<6) + Long.numberOfTrailingZeros(word);
      }
    }

    return DocIdSetIterator.NO_MORE_DOCS;
  }

  /** Returns the index of the last set bit before or on the index specified.
   *  -1 is returned if there are no more set bits.
   */
  public int prevSetBit(int index) {
    assert index >= 0 && index < numBits: "index=" + index + " numBits=" + numBits;
    int i = index >> 6;
    final int subIndex = index & 0x3f;  // index within the word
    long word = (bits[i] << (63-subIndex));  // skip all the bits to the left of index

    if (word != 0) {
      return (i << 6) + subIndex - Long.numberOfLeadingZeros(word); // See LUCENE-3197
    }

    while (--i >= 0) {
      word = bits[i];
      if (word !=0 ) {
        return (i << 6) + 63 - Long.numberOfLeadingZeros(word);
      }
    }

    return -1;
  }

  @Override
  public void or(DocIdSetIterator iter) throws IOException {
    if (BitSetIterator.getFixedBitSetOrNull(iter) != null) {
      assertUnpositioned(iter);
      final FixedBitSet bits = BitSetIterator.getFixedBitSetOrNull(iter); 
      or(bits);
    } else {
      super.or(iter);
    }
  }

  /** this = this OR other */
  public void or(FixedBitSet other) {
    or(other.bits, other.numWords);
  }
  
  private void or(final long[] otherArr, final int otherNumWords) {
    assert otherNumWords <= numWords : "numWords=" + numWords + ", otherNumWords=" + otherNumWords;
    final long[] thisArr = this.bits;
    int pos = Math.min(numWords, otherNumWords);
    while (--pos >= 0) {
      thisArr[pos] |= otherArr[pos];
    }
  }
  
  /** this = this XOR other */
  public void xor(FixedBitSet other) {
    xor(other.bits, other.numWords);
  }
  
  /** Does in-place XOR of the bits provided by the iterator. */
  public void xor(DocIdSetIterator iter) throws IOException {
    assertUnpositioned(iter);
    if (BitSetIterator.getFixedBitSetOrNull(iter) != null) {
      final FixedBitSet bits = BitSetIterator.getFixedBitSetOrNull(iter); 
      xor(bits);
    } else {
      int doc;
      while ((doc = iter.nextDoc()) < numBits) {
        flip(doc);
      }
    }
  }

  private void xor(long[] otherBits, int otherNumWords) {
    assert otherNumWords <= numWords : "numWords=" + numWords + ", other.numWords=" + otherNumWords;
    final long[] thisBits = this.bits;
    int pos = Math.min(numWords, otherNumWords);
    while (--pos >= 0) {
      thisBits[pos] ^= otherBits[pos];
    }
  }

  @Override
  public void and(DocIdSetIterator iter) throws IOException {
    if (BitSetIterator.getFixedBitSetOrNull(iter) != null) {
      assertUnpositioned(iter);
      final FixedBitSet bits = BitSetIterator.getFixedBitSetOrNull(iter); 
      and(bits);
    } else {
      super.and(iter);
    }
  }

  /** returns true if the sets have any elements in common */
  public boolean intersects(FixedBitSet other) {
    int pos = Math.min(numWords, other.numWords);
    while (--pos>=0) {
      if ((bits[pos] & other.bits[pos]) != 0) return true;
    }
    return false;
  }

  /** this = this AND other */
  public void and(FixedBitSet other) {
    and(other.bits, other.numWords);
  }
  
  private void and(final long[] otherArr, final int otherNumWords) {
    final long[] thisArr = this.bits;
    int pos = Math.min(this.numWords, otherNumWords);
    while(--pos >= 0) {
      thisArr[pos] &= otherArr[pos];
    }
    if (this.numWords > otherNumWords) {
      Arrays.fill(thisArr, otherNumWords, this.numWords, 0L);
    }
  }

  @Override
  public void andNot(DocIdSetIterator iter) throws IOException {
    if (BitSetIterator.getFixedBitSetOrNull(iter) != null) {
      assertUnpositioned(iter);
      final FixedBitSet bits = BitSetIterator.getFixedBitSetOrNull(iter); 
      andNot(bits);
    } else {
      super.andNot(iter);
    }
  }

  /** this = this AND NOT other */
  public void andNot(FixedBitSet other) {
    andNot(other.bits, other.bits.length);
  }
  
  private void andNot(final long[] otherArr, final int otherNumWords) {
    final long[] thisArr = this.bits;
    int pos = Math.min(this.numWords, otherNumWords);
    while(--pos >= 0) {
      thisArr[pos] &= ~otherArr[pos];
    }
  }

  // NOTE: no .isEmpty() here because that's trappy (ie,
  // typically isEmpty is low cost, but this one wouldn't
  // be)

  /** Flips a range of bits
   *
   * @param startIndex lower index
   * @param endIndex one-past the last bit to flip
   */
  public void flip(int startIndex, int endIndex) {
    assert startIndex >= 0 && startIndex < numBits;
    assert endIndex >= 0 && endIndex <= numBits;
    if (endIndex <= startIndex) {
      return;
    }

    int startWord = startIndex >> 6;
    int endWord = (endIndex-1) >> 6;

    /*** Grrr, java shifting wraps around so -1L>>>64 == -1
     * for that reason, make sure not to use endmask if the bits to flip will
     * be zero in the last word (redefine endWord to be the last changed...)
    long startmask = -1L << (startIndex & 0x3f);     // example: 11111...111000
    long endmask = -1L >>> (64-(endIndex & 0x3f));   // example: 00111...111111
    ***/

    long startmask = -1L << startIndex;
    long endmask = -1L >>> -endIndex;  // 64-(endIndex&0x3f) is the same as -endIndex due to wrap

    if (startWord == endWord) {
      bits[startWord] ^= (startmask & endmask);
      return;
    }

    bits[startWord] ^= startmask;

    for (int i=startWord+1; i<endWord; i++) {
      bits[i] = ~bits[i];
    }

    bits[endWord] ^= endmask;
  }

  /** Flip the bit at the provided index. */
  public void flip(int index) {
    assert index >= 0 && index < numBits: "index=" + index + " numBits=" + numBits;
    int wordNum = index >> 6;      // div 64
    int bit = index & 0x3f;     // mod 64
    long bitmask = 1L << bit;
    bits[wordNum] ^= bitmask;
  }

  /** Sets a range of bits
   *
   * @param startIndex lower index
   * @param endIndex one-past the last bit to set
   */
  public void set(int startIndex, int endIndex) {
    assert startIndex >= 0 && startIndex < numBits;
    assert endIndex >= 0 && endIndex <= numBits;
    if (endIndex <= startIndex) {
      return;
    }

    int startWord = startIndex >> 6;
    int endWord = (endIndex-1) >> 6;

    long startmask = -1L << startIndex;
    long endmask = -1L >>> -endIndex;  // 64-(endIndex&0x3f) is the same as -endIndex due to wrap

    if (startWord == endWord) {
      bits[startWord] |= (startmask & endmask);
      return;
    }

    bits[startWord] |= startmask;
    Arrays.fill(bits, startWord+1, endWord, -1L);
    bits[endWord] |= endmask;
  }

  @Override
  public void clear(int startIndex, int endIndex) {
    assert startIndex >= 0 && startIndex < numBits : "startIndex=" + startIndex + ", numBits=" + numBits;
    assert endIndex >= 0 && endIndex <= numBits : "endIndex=" + endIndex + ", numBits=" + numBits;
    if (endIndex <= startIndex) {
      return;
    }

    int startWord = startIndex >> 6;
    int endWord = (endIndex-1) >> 6;

    long startmask = -1L << startIndex;
    long endmask = -1L >>> -endIndex;  // 64-(endIndex&0x3f) is the same as -endIndex due to wrap

    // invert masks since we are clearing
    startmask = ~startmask;
    endmask = ~endmask;

    if (startWord == endWord) {
      bits[startWord] &= (startmask | endmask);
      return;
    }

    bits[startWord] &= startmask;
    Arrays.fill(bits, startWord+1, endWord, 0L);
    bits[endWord] &= endmask;
  }

  @Override
  public FixedBitSet clone() {
    long[] bits = new long[this.bits.length];
    System.arraycopy(this.bits, 0, bits, 0, bits.length);
    return new FixedBitSet(bits, numBits);
  }

  /** returns true if both sets have the same bits set */
  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof FixedBitSet)) {
      return false;
    }
    FixedBitSet other = (FixedBitSet) o;
    if (numBits != other.length()) {
      return false;
    }
    return Arrays.equals(bits, other.bits);
  }

  @Override
  public int hashCode() {
    long h = 0;
    for (int i = numWords; --i>=0;) {
      h ^= bits[i];
      h = (h << 1) | (h >>> 63); // rotate left
    }
    // fold leftmost bits into right and add a constant to prevent
    // empty sets from returning 0, which is too common.
    return (int) ((h>>32) ^ h) + 0x98761234;
  }
}
