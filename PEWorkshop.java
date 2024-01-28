/*
 *  Copyright 2023 The original authors
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package dev.morling.onebrc;

import sun.misc.Unsafe;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.reflect.Field;
import java.nio.channels.FileChannel;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class CalculateAverage_PEWorkshop {

    /**
     * According to the profile of the version 3, 57% of the time is spent in the ConcurrentHashMap
     * Mostly because each thread is continuously trying to read/write it. It's better for each
     * thread to have its own Map which gets merged at the end into a giant Map
     */
    private static final String FILE_NAME = "./measurements.txt";

    private static Unsafe initUnsafe() {
        try {
            final Field theUnsafe = Unsafe.class.getDeclaredField("theUnsafe");
            theUnsafe.setAccessible(true);
            return (Unsafe) theUnsafe.get(Unsafe.class);
        }
        catch (NoSuchFieldException | IllegalAccessException e) {
            throw new RuntimeException(e);
        }
    }

    private final static Unsafe UNSAFE = initUnsafe();

    private record Row(float minTemp, float maxTemp, int count, float sum) {
        Row update(float temperature) {
            float minTemp = Float.min(this.minTemp, temperature);
            float maxTemp = Float.max(this.maxTemp, temperature);
            return new Row(minTemp, maxTemp, this.count + 1, this.sum  +temperature);
        }

        public static Row create(float temperature) {
            return new Row(temperature, temperature, 1, temperature);
        }

        @Override
        public String toString() {
            return String.format("%.1f/%.1f/%.1f", (this.minTemp), (this.sum/count), (maxTemp));
        }

        public Row update(Row value) {
            float minTemp = Float.min(this.minTemp, value.minTemp);
            float maxTemp = Float.max(this.maxTemp, value.maxTemp);
            int count = this.count + value.count;
            float sum = this.sum + value.sum;
            return new Row(minTemp, maxTemp, count, sum);
        }
    };

    private static final Map<String, Row> records = new ConcurrentHashMap<>();

    private static void processLine(byte[] barray, int size, int semiColonIndex, Map<String, Row> rowMap) {
        String name = new String(barray, 0, semiColonIndex);
        String temperatureStr = new String(barray, semiColonIndex + 1, size - semiColonIndex - 1);
        float temperature = Float.parseFloat(temperatureStr);
        rowMap.compute(name, (k, v) -> v == null ? Row.create(temperature) : v.update(temperature));
    }

    private static Map<String, Row> readFile(long startAddress, long endAddress) {
        Map<String, Row> rowMap = new HashMap<>(10000);
        long currentOffset = startAddress;
        byte[] barray = new byte[512];
        int barrayOffset = 0;
        while (currentOffset < endAddress) {
            byte b;
            int semiColonIndex = -1;
            while ((b = UNSAFE.getByte(currentOffset++)) != '\n') {
                if (b == ';') {
                    semiColonIndex = barrayOffset;
                }
                barray[barrayOffset++] = b;
            }
            processLine(barray, barrayOffset, semiColonIndex, rowMap);
            barrayOffset = 0;
        }
        return rowMap;

    }

    public static void main(String[] args) throws IOException {
        String filename = args.length > 0 ? args[0] : FILE_NAME;
        FileChannel fc = FileChannel.open(Paths.get(filename), StandardOpenOption.READ);
        final long fileSize = fc.size();
        final long startAddress = fc.map(FileChannel.MapMode.READ_ONLY, 0, fileSize, Arena.global()).address();
        final long endAddress = startAddress + fileSize;
        final long[][] segments = findSegments(startAddress, endAddress, fileSize, fileSize > 1024 * 1024 * 1024 ? 6 : 1);
        final List<Map<String, Row>> collect = Arrays.stream(segments).parallel().map(s -> readFile(s[0], s[1])).toList();
        Map<String, Row> finalMap = new TreeMap<>(collect.getFirst());
        for (int i = 1; i < collect.size(); i++) {
            final Map<String, Row> rowMap = collect.get(i);
            for (Map.Entry<String, Row> e : rowMap.entrySet()) {
                finalMap.compute(e.getKey(), (_, v) -> v == null ? v : v.update(e.getValue()));
            }
        }
        System.out.println(finalMap);
    }

    private static long[][] findSegments(long startAddress, long endAddress, long size, int segmentCount) {
        if (segmentCount == 1) {
            return new long[][]{ { startAddress, endAddress } };
        }
        long[][] segments = new long[segmentCount][2];
        long segmentSize = size / segmentCount + 1;
        int i = 0;
        long currentOffset = startAddress;
        while (currentOffset < endAddress) {
            segments[i][0] = currentOffset;
            currentOffset += segmentSize;
            currentOffset = Math.min(currentOffset, endAddress);
            if (currentOffset >= endAddress) {
                segments[i][1] = endAddress;
                break;
            }
            while (UNSAFE.getByte(currentOffset) != '\n') {
                currentOffset++;
                // align to newline boundary
            }
            segments[i++][1] = currentOffset++;
        }
        return segments;
    }
}
