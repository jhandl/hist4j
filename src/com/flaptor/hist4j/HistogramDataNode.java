/*
Copyright 2007 Flaptor (flaptor.com) 

Licensed under the Apache License, Version 2.0 (the "License"); 
you may not use this file except in compliance with the License. 
You may obtain a copy of the License at 

    http://www.apache.org/licenses/LICENSE-2.0 

Unless required by applicable law or agreed to in writing, software 
distributed under the License is distributed on an "AS IS" BASIS, 
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. 
See the License for the specific language governing permissions and 
limitations under the License.
*/


package com.flaptor.hist4j;

/**
 * The HistogramDataNode stores the histogram data for a range of values.
 * It knows the minimum and maximum values for which it counts the number of instances.
 * When the count exceeds the allowed limit it splits itself in two, increasing the
 * histogram resolution for this range.
 * @author Jorge Handl
 */

public class HistogramDataNode extends HistogramNode {

    // Attributes of a data node.
    private long count;
    private float minValue, maxValue;

    /**
     * Creates an empty data node.
     */
    public HistogramDataNode () {
        reset();
    }

    /**
     * Creates a data node for the given range with the given instance count.
     * @param count the number of data instances in the given range.
     * @param minValue the start of the range of counted values.
     * @param maxValue the end of the range of counted values.
     */
    public HistogramDataNode (long count, float minValue, float maxValue) {
        reset();
        this.count = count;
        this.minValue = minValue;
        this.maxValue = maxValue;
    }

    /**
     * Clears the data node.
     */
    public void reset () {
        count = 0;
        minValue = Float.MAX_VALUE;
        maxValue = -Float.MAX_VALUE;
    }

    /**
     * Adds a value to the data node.<p>
     * If the value falls inside of the nodes' range and the count does not exceed the imposed limit, it simply increments the count.<br>
     * If the value falls outside of the nodes' range, it expands the range.<br>
     * If the count exceeds the limit, it splits in two assuming uniform distribution inside the node.<br>
     * If the value falls outside of the nodes' range AND the count exceeds the limit, it creates a new node for that value.
     * @param root a reference to the adaptive histogram instance that uses this structure.
     * @param value the value for which the count is to be incremented.
     * @return A reference to itself if no structural change happened, or a reference to the new fork node if this node was split.
     */
    public HistogramNode addValue (AdaptiveHistogram root, float value) {
        // "self" is what is returned to the caller. If this node needs to be replaced by a fork node, 
        // this variable will hold the new fork node and it will be returned to the caller.
        // Otherwise, the node returned will be this, in which case nothing changes.
        HistogramNode self = this; 
        if (value >= minValue && value <= maxValue) {  // the value falls within this nodes' range
            if (count < root.getCountPerNodeLimit()) {  // there is enough room in this node for the new value
                count++;
            } else {  // not enough room, distribute the value count among the new nodes, assuming uniform distribution
                float splitValue = (minValue + maxValue) / 2;
                long rightCount = count / 2;
                long leftCount = rightCount;
                boolean countWasOdd = (leftCount + rightCount < count);
                // assign the new value to the corresponding side. If the count is odd, add the extra item to the other side to keep balance
                if (value > splitValue) {
                    rightCount++;
                    leftCount += (countWasOdd?1:0);
                } else {
                    leftCount++;
                    rightCount += (countWasOdd?1:0);
                }
                // create a new subtree that will replace this node
                HistogramNode leftNode = new HistogramDataNode(leftCount, minValue, splitValue);
                HistogramNode rightNode = new HistogramDataNode(rightCount, splitValue, maxValue);
                self = new HistogramForkNode(splitValue, leftNode, rightNode);
            }
        } else {  // the value falls outside of this nodes' range
            if (count < root.getCountPerNodeLimit()) {  // there is enough room in this node for the new value
                count++;
                // extend the range of this node, assuming that the tree structure above correctly directed 
                // the given value to this node and therefore it lies at one of the borders of the tree.
                if (value < minValue) minValue = value;
                if (value > maxValue) maxValue = value;
            } else {  // not enough room, create a new sibling node for the new value and put both under a new fork node
                if (value < minValue) {
                    minValue = Math.min(minValue, (value + maxValue) / 2);
                    self = new HistogramForkNode(minValue, new HistogramDataNode(1,value,minValue), this);
                } else {
                    maxValue = Math.max(maxValue, (minValue + value) / 2);
                    self = new HistogramForkNode(maxValue, this, new HistogramDataNode(1,maxValue,value));
                }
            }
        }
        return self;
    }

    /**
     * Returns the number of data points stored in the same bucket as a given value.
     * @param value the reference data point.
     * @return the number of data points stored in the same bucket as the reference point.
     */
    public long getCount (float value) {
        long res = 0;
        if (value >= minValue && value <= maxValue) {
            res = count;
        }
        return res;
    }

    /**
     * Returns the cumulative density function for a given data point.
     * @param value the reference data point.
     * @return the cumulative density function for the reference point.
     */
    public long getAccumCount (float value) {
        long res = 0;
        if (value >= minValue) {
            res = count;
        }
        return res;
    }

    // Linear interpolation for float values.
    private float interpolate (float x0, float y0, float x1, float y1, float x) {
        return y0+((x-x0)*(y1-y0))/(x1-x0);
    }


    /**
     * Returns the data point where the running cumulative count reaches the target cumulative count.
     * It uses linear interpolation over the range of the node to get a better estimate of the true value.
     * @param accumCount an array containing:<br>
     *      - accumCount[0] the running cumulative count. <br>
     *      - accumCount[1] the target cumulative count.
     * @return the data point where the running cumulative count reaches the target cumulative count.
     */
    public Float getValueForAccumCount (long[] accumCount) {
        Float res = null;
        long runningAccumCount = accumCount[0];
        long targetAccumCount = accumCount[1];
        if (runningAccumCount <= targetAccumCount && runningAccumCount + count >= targetAccumCount) {
            float val = interpolate((float)runningAccumCount, minValue, (float)(runningAccumCount + count), maxValue, (float)targetAccumCount); 
            res = new Float(val);
        }
        accumCount[0] += count;
        return res;
    }

    /** 
     * Applies a convertion function to the values stored in the histogram.
     * @param valueConversion a class that defines a function to convert the value.
     */
    public void apply (AdaptiveHistogram.ValueConversion valueConversion) {
        minValue = valueConversion.convertValue(minValue);
        maxValue = valueConversion.convertValue(maxValue);
    }


    /**
     * Prints this nodes' data with a margin depending on the level of the node in the tree.
     * @param level the level of this node in the tree.
     */
    public void show (int level) {
        margin(level);
        System.out.println("Data: " + count + " ("+minValue+"-"+maxValue+")");
    }

}
