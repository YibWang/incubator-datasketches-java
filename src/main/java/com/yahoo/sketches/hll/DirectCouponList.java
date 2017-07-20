/*
 * Copyright 2017, Yahoo! Inc. Licensed under the terms of the
 * Apache License 2.0. See LICENSE file at the project root for terms.
 */

package com.yahoo.sketches.hll;

import static com.yahoo.sketches.hll.HllUtil.COUPON_RSE;
import static com.yahoo.sketches.hll.HllUtil.COUPON_RSE_FACTOR;
import static com.yahoo.sketches.hll.HllUtil.EMPTY;
import static com.yahoo.sketches.hll.HllUtil.LG_INIT_LIST_SIZE;
import static com.yahoo.sketches.hll.HllUtil.LG_INIT_SET_SIZE;
import static com.yahoo.sketches.hll.HllUtil.noWriteAccess;
import static com.yahoo.sketches.hll.PreambleUtil.EMPTY_FLAG_MASK;
import static com.yahoo.sketches.hll.PreambleUtil.HASH_SET_INT_ARR_START;
import static com.yahoo.sketches.hll.PreambleUtil.HASH_SET_PREINTS;
import static com.yahoo.sketches.hll.PreambleUtil.LG_K_BYTE;
import static com.yahoo.sketches.hll.PreambleUtil.LIST_INT_ARR_START;
import static com.yahoo.sketches.hll.PreambleUtil.LIST_PREINTS;
import static com.yahoo.sketches.hll.PreambleUtil.extractCurMode;
import static com.yahoo.sketches.hll.PreambleUtil.extractInt;
import static com.yahoo.sketches.hll.PreambleUtil.extractLgArr;
import static com.yahoo.sketches.hll.PreambleUtil.extractLgK;
import static com.yahoo.sketches.hll.PreambleUtil.extractListCount;
import static com.yahoo.sketches.hll.PreambleUtil.extractOooFlag;
import static com.yahoo.sketches.hll.PreambleUtil.extractTgtHllType;
import static com.yahoo.sketches.hll.PreambleUtil.insertCompactFlag;
import static com.yahoo.sketches.hll.PreambleUtil.insertEmptyFlag;
import static com.yahoo.sketches.hll.PreambleUtil.insertFamilyId;
import static com.yahoo.sketches.hll.PreambleUtil.insertFlags;
import static com.yahoo.sketches.hll.PreambleUtil.insertHashSetCount;
import static com.yahoo.sketches.hll.PreambleUtil.insertInt;
import static com.yahoo.sketches.hll.PreambleUtil.insertLgArr;
import static com.yahoo.sketches.hll.PreambleUtil.insertLgK;
import static com.yahoo.sketches.hll.PreambleUtil.insertListCount;
import static com.yahoo.sketches.hll.PreambleUtil.insertModes;
import static com.yahoo.sketches.hll.PreambleUtil.insertOooFlag;
import static com.yahoo.sketches.hll.PreambleUtil.insertPreInts;
import static com.yahoo.sketches.hll.PreambleUtil.insertSerVer;
import static java.lang.Math.max;

import com.yahoo.memory.Memory;
import com.yahoo.memory.WritableMemory;
import com.yahoo.sketches.SketchesStateException;

/**
 * @author Lee Rhodes
 */
class DirectCouponList extends HllSketchImpl {
  final int lgMaxCouponArrInts;
  WritableMemory wmem;
  Memory mem;
  Object memObj;
  long memAdd;

  //called from newInstance, writableWrap and DirectCouponHashSet
  DirectCouponList(final WritableMemory wmem, final int lgMaxCouponArrInts) {
    super();
    this.lgMaxCouponArrInts = lgMaxCouponArrInts;
    this.wmem = wmem;
    mem = wmem;
    memObj = wmem.getArray();
    memAdd = wmem.getCumulativeOffset(0L);
    HllSketch.checkPreamble(mem, memObj, memAdd); //TODO place earlier?
  }

  //called from local wrap and from DirectCouponHashSet
  DirectCouponList(final Memory mem, final int lgMaxCouponArrInts) {
    super();
    this.lgMaxCouponArrInts = lgMaxCouponArrInts;
    wmem = null;
    this.mem = mem;
    memObj = ((WritableMemory) mem).getArray();
    memAdd = mem.getCumulativeOffset(0L);
    HllSketch.checkPreamble(mem, memObj, memAdd); //TODO place earlier?
  }

  /**
   * Standard factory for new Direct LIST or SET.
   * This initializes the given WritableMemory.
   * @param lgConfigK the configured Lg K
   * @param tgtHllType the configured HLL target
   * @param curMode LIST or SET
   * @param dstMem the destination memory for the sketch.
   */
  static DirectCouponList newInstance(final int lgConfigK, final TgtHllType tgtHllType,
      final CurMode curMode, final WritableMemory dstMem) {
    final Object memObj = dstMem.getArray();
    final long memAdd = dstMem.getCumulativeOffset(0L);

    insertSerVer(memObj, memAdd);
    insertFamilyId(memObj, memAdd);
    insertLgK(memObj, memAdd, lgConfigK);
    insertFlags(memObj, memAdd, EMPTY_FLAG_MASK);
    insertListCount(memObj, memAdd, 0); //zero out for SET also
    insertModes(memObj, memAdd, tgtHllType, curMode);

    final long capBytes = dstMem.getCapacity();
    if (curMode == CurMode.LIST) {
      final int minBytes = LIST_INT_ARR_START + (4 << LG_INIT_LIST_SIZE);
      HllUtil.checkMemSize(minBytes, capBytes);
      insertPreInts(memObj, memAdd, LIST_PREINTS);
      insertLgArr(memObj, memAdd, LG_INIT_LIST_SIZE);
      return new DirectCouponList(dstMem, LG_INIT_LIST_SIZE);
    }
    //else SET
    final int minBytes = HASH_SET_INT_ARR_START + (4 << LG_INIT_SET_SIZE);
    HllUtil.checkMemSize(minBytes, capBytes);
    insertPreInts(memObj, memAdd, HASH_SET_PREINTS);

    insertLgArr(memObj, memAdd, LG_INIT_SET_SIZE);
    insertHashSetCount(memObj, memAdd, 0);
    return new DirectCouponHashSet(dstMem, lgConfigK);
  }

  /**
   * Wraps the given WritableMemory that is an image of a valid sketch with data.
   * @param srcMem an image of a valid sketch with data.
   * @return a DirectCouponList
   */
  static final DirectCouponList writableWrap(final WritableMemory srcMem, final CurMode curMode) {
    if (curMode == CurMode.LIST) {
      return new DirectCouponList(srcMem, LG_INIT_LIST_SIZE);
    }
    return new DirectCouponHashSet(srcMem, srcMem.getByte(LG_K_BYTE));
  }

  /**
   * Wraps the given Memory that is an image of a valid sketch with data.
   * @param srcMem an image of a valid sketch with data.
   * @return a DirectCouponList
   */
  static final DirectCouponList wrap(final Memory srcMem, final CurMode curMode) {
    if (curMode == CurMode.LIST) {
      return new DirectCouponList(srcMem, LG_INIT_LIST_SIZE);
    }
    return new DirectCouponHashSet(srcMem, srcMem.getByte(LG_K_BYTE));
  }

  @Override
  AuxHashMap getAuxHashMap() {
    return null;
  }

  @Override //returns on-heap List
  CouponList copy() {
    return CouponList.heapifyList(mem);
  }

  @Override //returns on-heap List
  CouponList copyAs(final TgtHllType tgtHllType) {
    final CouponList clist = CouponList.heapifyList(mem);
    return new CouponList(clist, tgtHllType);
  }

  @Override
  HllSketchImpl couponUpdate(final int coupon) {
    if (wmem == null) { noWriteAccess(); }
    final int len = 1 << getLgCouponArrInts();
    final int lgConfigK = getLgConfigK();
    for (int i = 0; i < len; i++) { //search for empty slot
      final int couponAtIdx = extractInt(memObj, memAdd, i << 2);
      if (couponAtIdx == EMPTY) {
        insertInt(memObj, memAdd, LIST_INT_ARR_START + (i << 2), coupon);
        int couponCount = extractListCount(memObj, memAdd);
        insertListCount(memObj, memAdd, ++couponCount);
        insertEmptyFlag(memObj, memAdd, false);
        if (couponCount >= len) { //array full
          if (lgConfigK < 8) {
            return DirectCouponHashSet
                .morphFromCouponsToHll(this, lgConfigK, getTgtHllType());//oooFlag = false
          }
          return DirectCouponHashSet.morphFromListToSet(this); //oooFlag = true
        }
        return this;
      }
      //cell not empty
      if (couponAtIdx == coupon) { return this; } //duplicate
      //cell not empty & not a duplicate, continue
    } //end for
    throw new SketchesStateException("Array invalid: no empties & no duplicates");
  }

  @Override
  PairIterator getAuxIterator() {
    return null; //always null from LIST or SET
  }

  @Override
  int getCouponCount() {
    return extractListCount(memObj, memAdd);
  }

  @Override
  int[] getCouponIntArr() { //expensive, use sparingly
    final int len = 1 << getLgCouponArrInts();
    final int[] intArr = new int[len];
    mem.getIntArray(LIST_INT_ARR_START, intArr, 0, len);
    return intArr;
  }

  @Override
  int getCurMin() {
    return -1;
  }

  @Override
  final CurMode getCurMode() {
    return extractCurMode(memObj, memAdd);
  }

  @Override
  int getCompactSerializationBytes() {
    return LIST_INT_ARR_START + (getCouponCount() << 2);
  }

  @Override
  double getCompositeEstimate() {
    return getEstimate();
  }

  @Override
  double getEstimate() {
    final int couponCount = getCouponCount();
    final double est = CubicInterpolation.usingXAndYTables(CouponMapping.xArr,
        CouponMapping.yArr, couponCount);
    return max(est, couponCount);
  }

  @Override
  double getHipAccum() {
    return getCouponCount();
  }

  @Override
  byte[] getHllByteArr() {
    return null;
  }

  @Override
  PairIterator getIterator() {
    return new DirectCouponIterator();
  }

  @Override
  double getKxQ0() {
    return 0;
  }

  @Override
  double getKxQ1() {
    return 0;
  }

  @Override
  int getLgConfigK() {
    return extractLgK(memObj, memAdd);
  }

  @Override
  int getLgCouponArrInts() {
    return extractLgArr(memObj, memAdd);
  }

  @Override
  int getLgMaxCouponArrInts() {
    return lgMaxCouponArrInts;
  }

  @Override
  double getLowerBound(final int numStdDev) {
    final int couponCount = getCouponCount();
    final double est = CubicInterpolation.usingXAndYTables(CouponMapping.xArr,
        CouponMapping.yArr, couponCount);
    final double tmp = est / (1.0 + CouponList.couponEstimatorEps(numStdDev));
    return max(tmp, couponCount);
  }

  @Override
  int getNumAtCurMin() {
    return -1;
  }

  @Override
  double getRelErr(final int numStdDev) {
    HllUtil.checkNumStdDev(numStdDev);
    return numStdDev * COUPON_RSE;
  }

  @Override
  double getRelErrFactor(final int numStdDev) {
    HllUtil.checkNumStdDev(numStdDev);
    return numStdDev * COUPON_RSE_FACTOR;
  }

  @Override
  TgtHllType getTgtHllType() {
    return extractTgtHllType(memObj, memAdd);
  }

  @Override
  int getUpdatableSerializationBytes() {
    if (getCurMode() == CurMode.LIST) {
      return LIST_INT_ARR_START + (4 << getLgCouponArrInts());
    }
    return HASH_SET_INT_ARR_START + (4 << getLgCouponArrInts());
  }

  @Override
  double getUpperBound(final int numStdDev) {
    final int couponCount = getCouponCount();
    final double est = CubicInterpolation.usingXAndYTables(CouponMapping.xArr,
        CouponMapping.yArr, couponCount);
    final double tmp = est / (1.0 - CouponList.couponEstimatorEps(numStdDev));
    return max(tmp, couponCount);
  }

  @Override
  boolean isEmpty() {
    return getCouponCount() == 0;
  }

  @Override
  boolean isOutOfOrderFlag() {
    return extractOooFlag(memObj, memAdd);
  }

  @Override
  void putCouponCount(final int couponCount) {
    assert wmem != null;
    insertListCount(memObj, memAdd, couponCount);
  }

  void putCouponIntArr(final int[] couponIntArr, final int lgCouponArrInts) {
    assert wmem != null;
    final int len = 1 << lgCouponArrInts;
    wmem.putIntArray(LIST_INT_ARR_START, couponIntArr, 0, len);
    insertLgArr(memObj, memAdd, lgCouponArrInts);
  }

  @Override
  void putOutOfOrderFlag(final boolean oooFlag) {
    assert wmem != null;
    insertOooFlag(memObj, memAdd, oooFlag);
  }

  @Override //for both List and Set
  byte[] toCompactByteArray() {
    return toByteArray(true);
  }

  @Override //for both List and Set
  byte[] toUpdatableByteArray() {
    return toByteArray(false);
  }

  private byte[] toByteArray(final boolean compact) {
    final byte[] memArrOut;
    final WritableMemory wmemOut;
    final long memAddOut;
    final int couponCount = getCouponCount();

    if (getCurMode() == CurMode.LIST) {
      memArrOut = new byte[LIST_INT_ARR_START + (couponCount << 2)];
      wmemOut = WritableMemory.wrap(memArrOut);
      memAddOut = wmemOut.getCumulativeOffset(0);
      insertPreInts(memArrOut, memAddOut, LIST_PREINTS);
      insertListCount(memArrOut, memAddOut, couponCount);
      insertCompactFlag(memArrOut, memAddOut, compact);
      CouponList.insertCommonList(this, memArrOut, memAddOut);
      mem.copyTo(LIST_INT_ARR_START, wmemOut, LIST_INT_ARR_START, couponCount << 2);

    } else { //SET
      final int lgCouponArrInts = getLgCouponArrInts();
      final int len = (compact) ? couponCount << 2 : 4 << lgCouponArrInts;
      memArrOut = new byte[HASH_SET_INT_ARR_START + len];
      wmemOut = WritableMemory.wrap(memArrOut);
      memAddOut = wmemOut.getCumulativeOffset(0);
      insertPreInts(memArrOut, memAddOut, HASH_SET_PREINTS);
      insertHashSetCount(memArrOut, memAddOut, couponCount);
      insertCompactFlag(memArrOut, memAddOut, compact);
      CouponList.insertCommonList(this, memArrOut, memAddOut);

      if (compact) {
        final PairIterator itr = getIterator();
        int cnt = 0;
        while (itr.nextValid()) {
          wmemOut.putInt(HASH_SET_INT_ARR_START + (cnt++ << 2), itr.getPair());
        }
      } else { //updatable
        mem.copyTo(HASH_SET_INT_ARR_START, wmemOut, HASH_SET_INT_ARR_START, 1 << lgCouponArrInts);
      }
    }
    return memArrOut;
  }

  //Iterator for SET and LIST

  final class DirectCouponIterator implements PairIterator {
    final int len;
    final int start;
    int index;
    int coupon;

    DirectCouponIterator() {
      final CurMode curMode = extractCurMode(memObj, memAdd);
      start = (curMode == CurMode.LIST) ? LIST_INT_ARR_START : HASH_SET_INT_ARR_START;
      len = 1 << getLgCouponArrInts();
      index = - 1;
    }

    @Override
    public boolean nextValid() {
      while (++index < len) {
        final int coupon = extractInt(memObj, memAdd, start + (index << 2));
        if (coupon != EMPTY) {
          this.coupon = coupon;
          return true;
        }
      }
      return false;
    }

    @Override
    public boolean nextAll() {
      if (++index < len) {
        coupon = extractInt(memObj, memAdd, start + (index << 2));
        return true;
      }
      return false;
    }

    @Override
    public int getPair() {
      return coupon;
    }

    @Override
    public int getKey() {
      return BaseHllSketch.getLow26(coupon);
    }

    @Override
    public int getValue() {
      return BaseHllSketch.getValue(coupon);
    }

    @Override
    public int getIndex() {
      return index;
    }
  }
  //END Iterators

}