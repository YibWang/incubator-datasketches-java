/*
 * Copyright 2016, Yahoo! Inc. Licensed under the terms of the
 * Apache License 2.0. See LICENSE file at the project root for terms.
 */

package com.yahoo.sketches.hll;

import static com.yahoo.sketches.Util.invPow2;

import java.util.Arrays;

import com.yahoo.sketches.SketchesArgumentException;
import com.yahoo.sketches.hash.MurmurHash3;

/**
 * Implements a key-value map where the value is a compact HLL sketch of size k.
 * The HLL bins are compacted into 10 bins per long so that a 1024 bins are compacted into
 * 824 bytes, which is a 20% reduction in space. Higher density compressions are possible
 * (up to 50%), but the required code is much more complex and considerably slower.
 *
 * <p>Each entry row, associated with a key, also contains 3 double registers for accurately
 * tracking the HIP (Historical Inverse Probability) estimator. HLL implementations have multiple
 * estimators and the early estimators in this implementation are quite novel and provide superior
 * error performance over most other HLL implementations.
 *
 * @author Lee Rhodes
 * @author KevinLang
 * @author Alexander Saydakov
 */
class HllMap extends Map {
  public static final String LS = System.getProperty("line.separator");
  private static final double LOAD_FACTOR = 15.0 / 16.0;
  private static final int HLL_INIT_NUM_ENTRIES = 157;
  private static final float HLL_RESIZE_FACTOR = 2.0F;
  private static final double RSE = 0.836 / Math.sqrt(1024);
  private final int k_;
  private final int hllArrLongs_;
  private final double entrySizeBytes_;

  private int tableEntries_; //Full size of the table
  private int capacityEntries_; //max capacity entries defined by Load factor
  private int curCountEntries_; //current count of valid entries
  private float growthFactor_;  //e.g., 1.2 to 2.0

  //Arrays
  private byte[] keysArr_; //keys of zero are allowed
  private long[] arrOfHllArr_;
  private double[] invPow2SumHiArr_;
  private double[] invPow2SumLoArr_;
  private double[] hipEstAccumArr_;
  private byte[] stateArr_;

  /**
   * Private constructor used to set all finals
   * @param keySizeBytes size of key in bytes
   * @param k size of HLL sketch
   */
  private HllMap(final int tableEntries, final int keySizeBytes, final int k) {
    super(keySizeBytes);
    k_ = k;
    hllArrLongs_ = k / 10 + 1;
    final double byteFraction = Math.ceil(tableEntries / 8.0) / tableEntries;
    entrySizeBytes_ = keySizeBytes + hllArrLongs_ * Long.BYTES + 3 * Double.BYTES + byteFraction;
  }

  static HllMap getInstance(final int keySizeBytes, final int k) {
    final int tableEntries = HLL_INIT_NUM_ENTRIES;

    final HllMap map = new HllMap(tableEntries, keySizeBytes, k);

    map.tableEntries_ = tableEntries;
    map.capacityEntries_ = (int)(tableEntries * LOAD_FACTOR);
    map.curCountEntries_ = 0;
    map.growthFactor_ = HLL_RESIZE_FACTOR;

    map.keysArr_ = new byte[tableEntries * map.keySizeBytes_];
    map.arrOfHllArr_ = new long[tableEntries * map.hllArrLongs_];
    map.invPow2SumHiArr_ = new double[tableEntries];
    map.invPow2SumLoArr_ = new double[tableEntries];
    map.hipEstAccumArr_ = new double[tableEntries];
    map.stateArr_ = new byte[(int) Math.ceil(tableEntries / 8.0)];
    return map;
  }

  @Override
  double update(final byte[] key, final int coupon) {
    final int entryIndex = findOrInsertKey(key);
    return findOrInsertCoupon(entryIndex, coupon);
  }

  @Override
  double getEstimate(final byte[] key) {
    if (key == null) return Double.NaN;
    final int entryIndex = findKey(keysArr_, key, tableEntries_, stateArr_);
    if (entryIndex < 0) {
      return 0;
    }
    return hipEstAccumArr_[entryIndex];
  }

  @Override
  double getUpperBound(byte[] key) {
    return getEstimate(key) * (1 + RSE);
  }

  @Override
  double getLowerBound(byte[] key) {
    return getEstimate(key) * (1 - RSE);
  }

  void updateEstimate(final int entryIndex, final double estimate) {
    hipEstAccumArr_[entryIndex] = estimate;
  }

  int findOrInsertKey(final byte[] key) {
    int entryIndex = findKey(keysArr_, key, tableEntries_, stateArr_);
    if (entryIndex < 0) { //key not found, initialize new row
      entryIndex = ~entryIndex;
      System.arraycopy(key, 0, keysArr_, entryIndex * keySizeBytes_, keySizeBytes_);
      setBit(stateArr_, entryIndex);
      invPow2SumHiArr_[entryIndex] = k_;
      invPow2SumLoArr_[entryIndex] = 0;
      hipEstAccumArr_[entryIndex] = 0;
      curCountEntries_++;
      if (curCountEntries_ > capacityEntries_) {
        resize();
        entryIndex = findKey(keysArr_, key, tableEntries_, stateArr_);
        assert entryIndex >= 0;
      }
    }
    return entryIndex;
  }

  double findOrInsertCoupon(final int entryIndex, final int coupon) {
    updateHll(entryIndex, coupon); //update HLL array, updates HIP
    return hipEstAccumArr_[entryIndex];
  }

  @Override
  double getEntrySizeBytes() {
    return entrySizeBytes_;
  }

  @Override
  int getTableEntries() {
    return tableEntries_;
  }

  @Override
  int getCapacityEntries() {
    return capacityEntries_;
  }

  @Override
  int getCurrentCountEntries() {
    return curCountEntries_;
  }

  @Override
  long getMemoryUsageBytes() {
    final long arrays = keysArr_.length
        + (long)arrOfHllArr_.length * Long.BYTES
        + invPow2SumLoArr_.length * Double.BYTES
        + invPow2SumHiArr_.length * Double.BYTES
        + hipEstAccumArr_.length * Double.BYTES
        + stateArr_.length;
    final long other = 5 * Integer.BYTES + Float.BYTES + Double.BYTES;
    return arrays + other;
  }

  @Override
  public String toString() {
    final String kStr = Map.fmtLong(k_);
    final String te = Map.fmtLong(getTableEntries());
    final String ce = Map.fmtLong(getCapacityEntries());
    final String cce = Map.fmtLong(getCurrentCountEntries());
    final String esb = Map.fmtDouble(getEntrySizeBytes());
    final String mub = Map.fmtLong(getMemoryUsageBytes());

    final StringBuilder sb = new StringBuilder();
    final String thisSimpleName = this.getClass().getSimpleName();
    sb.append("### ").append(thisSimpleName).append(" SUMMARY: ").append(LS);
    sb.append("    HLL k                     : ").append(kStr).append(LS);
    sb.append("    Table Entries             : ").append(te).append(LS);
    sb.append("    Capacity Entries          : ").append(ce).append(LS);
    sb.append("    Current Count Entries     : ").append(cce).append(LS);
    sb.append("    Entry Size Bytes          : ").append(esb).append(LS);
    sb.append("    Memory Usage Bytes        : ").append(mub).append(LS);
    sb.append("### END SKETCH SUMMARY").append(LS);
    return sb.toString();
  }

  /**
   * Returns the entry index for the given key given the array of keys, if found.
   * Otherwise, returns the one's complement of first empty entry found;
   * @param keyArr the given array of keys
   * @param key the key to search for
   * @param tableEntries the total number of entries in the table.
   * @param stateArr the bit vector that holds valid/empty state of each entry
   * @return the entry index of the given key, or the one's complement of the index if not found.
   */
  private static final int findKey(final byte[] keyArr, final byte[] key, final int tableEntries, final byte[] stateArr) {
    final int keyLen = key.length;
    final long[] hash = MurmurHash3.hash(key, SEED);
    int entryIndex  = getIndex(hash[0], tableEntries);
    final int stride = getStride(hash[1], tableEntries);
    final int loopIndex = entryIndex;

    do {
      if (isBitClear(stateArr, entryIndex)) { //check if slot is empty
        return ~entryIndex;
      }
      if (arraysEqual(key, 0, keyArr, entryIndex * keyLen, keyLen)) { //check for key match
        return entryIndex;
      }
      entryIndex = (entryIndex + stride) % tableEntries;
    } while (entryIndex != loopIndex);
    throw new SketchesArgumentException("Key not found and no empty slots!");
  }

  /**
   * Find the first empty slot for the given key.
   * Only used by resize, where it is known that the key does not exist in the table.
   * Throws an exception if no empty slots.
   * @param key the given key
   * @param tableEntries prime size of table
   * @param stateArr the valid bit array
   * @return the first empty slot for the given key
   */
  private static final int findEmpty(final byte[] key, final int tableEntries, final byte[] stateArr) {
    final long[] hash = MurmurHash3.hash(key, SEED);
    int entryIndex  = getIndex(hash[0], tableEntries);
    final int stride = getStride(hash[1], tableEntries);
    final int loopIndex = entryIndex;

    do {
      if (isBitClear(stateArr, entryIndex)) { //check if slot is empty
        return entryIndex;
      }
      entryIndex = (entryIndex + stride) % tableEntries;
    } while (entryIndex != loopIndex);
    throw new SketchesArgumentException("No empty slots.");
  }

  //This method is specifically tied to the HLL array layout
  private final boolean updateHll(final int entryIndex, final int coupon) {
    final int newValue = coupon16Value(coupon);

    final int hllIdx = coupon & (k_ - 1); //lower lgK bits
    final int longIdx = hllIdx / 10;
    final int shift = ((hllIdx % 10) * 6) & SIX_BIT_MASK;

    long hllLong = arrOfHllArr_[entryIndex * hllArrLongs_ + longIdx];
    final int oldValue = (int)(hllLong >>> shift) & SIX_BIT_MASK;
    if (newValue <= oldValue) return false;
    // newValue > oldValue

    //update hipEstAccum BEFORE updating invPow2Sum
    final double invPow2Sum = invPow2SumHiArr_[entryIndex] + invPow2SumLoArr_[entryIndex];
    final double oneOverQ = k_ / invPow2Sum;
    hipEstAccumArr_[entryIndex] += oneOverQ;

    //update invPow2Sum
    if (oldValue < 32) { invPow2SumHiArr_[entryIndex] -= invPow2(oldValue); }
    else               { invPow2SumLoArr_[entryIndex] -= invPow2(oldValue); }
    if (newValue < 32) { invPow2SumHiArr_[entryIndex] += invPow2(newValue); }
    else               { invPow2SumLoArr_[entryIndex] += invPow2(newValue); }

    //insert the new value
    hllLong &= ~(0X3FL << shift);  //zero out the 6-bit field
    hllLong |=  ((long)newValue) << shift; //insert
    arrOfHllArr_[entryIndex * hllArrLongs_ + longIdx] = hllLong;
    return true;
  }

  private final void resize() {
    final int newTableEntries = nextPrime((int)(tableEntries_ * growthFactor_));
    final int newCapacityEntries = (int)(newTableEntries * LOAD_FACTOR);

    final byte[] newKeysArr = new byte[newTableEntries * keySizeBytes_];
    final long[] newArrOfHllArr = new long[newTableEntries * hllArrLongs_];
    final double[] newInvPow2Sum1 = new double[newTableEntries];
    final double[] newInvPow2Sum2 = new double[newTableEntries];
    final double[] newHipEstAccum = new double[newTableEntries];
    final byte[] newStateArr = new byte[(int) Math.ceil(newTableEntries / 8.0)];

    for (int oldIndex = 0; oldIndex < tableEntries_; oldIndex++) {
      if (isBitClear(stateArr_, oldIndex)) continue;
      // extract an old key
      final byte[] key = Arrays.copyOfRange(keysArr_, oldIndex * keySizeBytes_, (oldIndex + 1) * keySizeBytes_);
      final int newIndex = findEmpty(key, newTableEntries, newStateArr);
      System.arraycopy(key, 0, newKeysArr, newIndex * keySizeBytes_, keySizeBytes_); //put key
      //put the rest of the row
      System.arraycopy(
          arrOfHllArr_, oldIndex * hllArrLongs_, newArrOfHllArr, newIndex * hllArrLongs_, hllArrLongs_);
      newInvPow2Sum1[newIndex] = invPow2SumHiArr_[oldIndex];
      newInvPow2Sum2[newIndex] = invPow2SumLoArr_[oldIndex];
      newHipEstAccum[newIndex] = hipEstAccumArr_[oldIndex];
      setBit(newStateArr, newIndex);
    }
    //restore into sketch
    tableEntries_ = newTableEntries;
    capacityEntries_ = newCapacityEntries;
    //curCountEntries_, growthFactor_  unchanged

    keysArr_ = newKeysArr;
    arrOfHllArr_ = newArrOfHllArr;
    invPow2SumHiArr_ = newInvPow2Sum1; //init to k
    invPow2SumLoArr_ = newInvPow2Sum2; //init to 0
    hipEstAccumArr_ = newHipEstAccum;  //init to 0
    stateArr_ = newStateArr;
  }

}
