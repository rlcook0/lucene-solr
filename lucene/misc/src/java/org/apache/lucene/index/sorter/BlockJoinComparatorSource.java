package org.apache.lucene.index.sorter;

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

import java.io.IOException;

import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.search.DocIdSet;
import org.apache.lucene.search.FieldComparator;
import org.apache.lucene.search.FieldComparatorSource;
import org.apache.lucene.search.Filter;
import org.apache.lucene.search.IndexSearcher; // javadocs
import org.apache.lucene.search.Query; // javadocs
import org.apache.lucene.search.ScoreDoc; // javadocs
import org.apache.lucene.search.Scorer;
import org.apache.lucene.search.Sort;
import org.apache.lucene.search.SortField;
import org.apache.lucene.util.BitDocIdSet;
import org.apache.lucene.util.FixedBitSet;

/**
 * Helper class to sort readers that contain blocks of documents.
 * <p>
 * Note that this class is intended to used with {@link SortingMergePolicy},
 * and for other purposes has some limitations:
 * <ul>
 *    <li>Cannot yet be used with {@link IndexSearcher#searchAfter(ScoreDoc, Query, int, Sort) IndexSearcher.searchAfter}
 *    <li>Filling sort field values is not yet supported.
 * </ul>
 * @lucene.experimental
 */
// TODO: can/should we clean this thing up (e.g. return a proper sort value)
// and move to the join/ module?
public class BlockJoinComparatorSource extends FieldComparatorSource {
  final Filter parentsFilter;
  final Sort parentSort;
  final Sort childSort;
  
  /** 
   * Create a new BlockJoinComparatorSource, sorting only blocks of documents
   * with {@code parentSort} and not reordering children with a block.
   * 
   * @param parentsFilter Filter identifying parent documents
   * @param parentSort Sort for parent documents
   */
  public BlockJoinComparatorSource(Filter parentsFilter, Sort parentSort) {
    this(parentsFilter, parentSort, new Sort(SortField.FIELD_DOC));
  }
  
  /** 
   * Create a new BlockJoinComparatorSource, specifying the sort order for both
   * blocks of documents and children within a block.
   * 
   * @param parentsFilter Filter identifying parent documents
   * @param parentSort Sort for parent documents
   * @param childSort Sort for child documents in the same block
   */
  public BlockJoinComparatorSource(Filter parentsFilter, Sort parentSort, Sort childSort) {
    this.parentsFilter = parentsFilter;
    this.parentSort = parentSort;
    this.childSort = childSort;
  }

  @Override
  public FieldComparator<Integer> newComparator(String fieldname, int numHits, int sortPos, boolean reversed) throws IOException {
    // we keep parallel slots: the parent ids and the child ids
    final int parentSlots[] = new int[numHits];
    final int childSlots[] = new int[numHits];
    
    SortField parentFields[] = parentSort.getSort();
    final int parentReverseMul[] = new int[parentFields.length];
    final FieldComparator<?> parentComparators[] = new FieldComparator[parentFields.length];
    for (int i = 0; i < parentFields.length; i++) {
      parentReverseMul[i] = parentFields[i].getReverse() ? -1 : 1;
      parentComparators[i] = parentFields[i].getComparator(1, i);
    }
    
    SortField childFields[] = childSort.getSort();
    final int childReverseMul[] = new int[childFields.length];
    final FieldComparator<?> childComparators[] = new FieldComparator[childFields.length];
    for (int i = 0; i < childFields.length; i++) {
      childReverseMul[i] = childFields[i].getReverse() ? -1 : 1;
      childComparators[i] = childFields[i].getComparator(1, i);
    }
        
    // NOTE: we could return parent ID as value but really our sort "value" is more complex...
    // So we throw UOE for now. At the moment you really should only use this at indexing time.
    return new FieldComparator<Integer>() {
      int bottomParent;
      int bottomChild;
      FixedBitSet parentBits;
      
      @Override
      public int compare(int slot1, int slot2) {
        try {
          return compare(childSlots[slot1], parentSlots[slot1], childSlots[slot2], parentSlots[slot2]);
        } catch (IOException e) {
          throw new RuntimeException(e);
        }
      }

      @Override
      public void setBottom(int slot) {
        bottomParent = parentSlots[slot];
        bottomChild = childSlots[slot];
      }

      @Override
      public void setTopValue(Integer value) {
        // we dont have enough information (the docid is needed)
        throw new UnsupportedOperationException("this comparator cannot be used with deep paging");
      }

      @Override
      public int compareBottom(int doc) throws IOException {
        return compare(bottomChild, bottomParent, doc, parent(doc));
      }

      @Override
      public int compareTop(int doc) throws IOException {
        // we dont have enough information (the docid is needed)
        throw new UnsupportedOperationException("this comparator cannot be used with deep paging");
      }

      @Override
      public void copy(int slot, int doc) throws IOException {
        childSlots[slot] = doc;
        parentSlots[slot] = parent(doc);
      }

      @Override
      public FieldComparator<Integer> setNextReader(LeafReaderContext context) throws IOException {
        final DocIdSet parents = parentsFilter.getDocIdSet(context, null);
        if (parents == null) {
          throw new IllegalStateException("LeafReader " + context.reader() + " contains no parents!");
        }
        if (!(parents instanceof BitDocIdSet)) {
          throw new IllegalStateException("parentFilter must return FixedBitSet; got " + parents);
        }
        parentBits = (FixedBitSet) parents.bits();
        for (int i = 0; i < parentComparators.length; i++) {
          parentComparators[i] = parentComparators[i].setNextReader(context);
        }
        for (int i = 0; i < childComparators.length; i++) {
          childComparators[i] = childComparators[i].setNextReader(context);
        }
        return this;
      }

      @Override
      public Integer value(int slot) {
        // really our sort "value" is more complex...
        throw new UnsupportedOperationException("filling sort field values is not yet supported");
      }
      
      @Override
      public void setScorer(Scorer scorer) {
        super.setScorer(scorer);
        for (FieldComparator<?> comp : parentComparators) {
          comp.setScorer(scorer);
        }
        for (FieldComparator<?> comp : childComparators) {
          comp.setScorer(scorer);
        }
      }

      int parent(int doc) {
        return parentBits.nextSetBit(doc);
      }
      
      int compare(int docID1, int parent1, int docID2, int parent2) throws IOException {
        if (parent1 == parent2) { // both are in the same block
          if (docID1 == parent1 || docID2 == parent2) {
            // keep parents at the end of blocks
            return docID1 - docID2;
          } else {
            return compare(docID1, docID2, childComparators, childReverseMul);
          }
        } else {
          int cmp = compare(parent1, parent2, parentComparators, parentReverseMul);
          if (cmp == 0) {
            return parent1 - parent2;
          } else {
            return cmp;
          }
        }
      }
      
      int compare(int docID1, int docID2, FieldComparator<?> comparators[], int reverseMul[]) throws IOException {
        for (int i = 0; i < comparators.length; i++) {
          // TODO: would be better if copy() didnt cause a term lookup in TermOrdVal & co,
          // the segments are always the same here...
          comparators[i].copy(0, docID1);
          comparators[i].setBottom(0);
          int comp = reverseMul[i] * comparators[i].compareBottom(docID2);
          if (comp != 0) {
            return comp;
          }
        }
        return 0; // no need to docid tiebreak
      }
    };
  }
  
  @Override
  public String toString() {
    return "blockJoin(parentSort=" + parentSort + ",childSort=" + childSort + ")";
  }
}
