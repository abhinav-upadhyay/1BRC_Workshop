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

public class CalculateAverage_PEWorkshop8 {

    /**
     *  Let's forget separation of concerns. Compute the hash of the location name as we read its bytes
     *  This totally eliminates the overhead of calling hashCode function when storing in table.
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

    private static class Table {
        private static final int TABLE_SIZE = 1 << 21; // collisions with table smaller than this. Need a better hash function
        private static final int TABLE_MASK = TABLE_SIZE - 1;
        Row[] table = new Row[TABLE_SIZE];

        public void put(byte[] nameBytes, int nameArraySize, int nameHash, int temperature) {
            int index = nameHash & TABLE_MASK;

            if (table[index] == null) {
                table[index] = Row.create(new String(nameBytes, 0, nameArraySize), temperature);
                return;
            }
            table[index].update(temperature);
        }

        private static int hashCode(byte[] a, int fromIndex, int length) {
            int result = 1;
            int end = fromIndex + length;
            for (int i = fromIndex; i < end; i++) {
                result = 31 * result + a[i];
            }
            return result;
        }

    }

    private static final class Row {
        private final String name;
        private int minTemp;
        private int maxTemp;
        private int count;
        private int sum;

        private Row(String name, int minTemp, int maxTemp, int count, int sum) {
            this.name = name;
            this.minTemp = minTemp;
            this.maxTemp = maxTemp;
            this.count = count;
            this.sum = sum;
        }

        void update(int temperature) {
            this.minTemp = Integer.min(this.minTemp, temperature);
            this.maxTemp = Integer.max(this.maxTemp, temperature);
            this.count++;
            this.sum += temperature;
        }

        public static Row create(String name, int temperature) {
            return new Row(name, temperature, temperature, 1, temperature);
        }

        @Override
        public String toString() {
            return String.format("%.1f/%.1f/%.1f", this.minTemp / 10.0, this.sum / (count * 10.0), maxTemp / 10.0);
        }

        public Row update(Row value) {
            this.minTemp = Integer.min(this.minTemp, value.minTemp);
            this.maxTemp = Integer.max(this.maxTemp, value.maxTemp);
            this.count += value.count;
            this.sum += value.sum;
            return this;
        }

        @Override
        public boolean equals(Object obj) {
            if (obj == this)
                return true;
            if (obj == null || obj.getClass() != this.getClass())
                return false;
            var that = (Row) obj;
            return Objects.equals(this.name, that.name) &&
                    this.minTemp == that.minTemp &&
                    this.maxTemp == that.maxTemp &&
                    this.count == that.count &&
                    this.sum == that.sum;
        }

        @Override
        public int hashCode() {
            return Objects.hash(name, minTemp, maxTemp, count, sum);
        }
    }

    private static int parseTemperature(byte[] array, int start, int size) {
        byte b = array[start++];
        int scale = 1;
        boolean isNegative = false;
        int temp = 0;
        if (b == '-') {
            isNegative = true;
        }
        else {
            temp += b - '0';
            scale = 10;
        }
        for (int i = start; i < size; i++) {
            b = array[i];
            if (b == '.') {
                continue;
            }
            temp = temp * scale + b - '0';
            scale = 10;
        }
        return isNegative ? temp * -1 : temp;
    }

    private static void processLine(byte[] nameArray, int nameArraySize, int nameHash, byte[] tempArray, int tempArraySize, Table table) {
        int temperature = parseTemperature(tempArray, 0, tempArraySize);
        table.put(nameArray, nameArraySize, nameHash, temperature);
    }

    private static Table readFile(long startAddress, long endAddress) {
        Table table = new Table();
        long currentOffset = startAddress;
        byte[] nameArray = new byte[512];
        int nameArrayOffset = 0;
        byte[] tempArray = new byte[8];
        int tempArrayOffset = 0;
        int nameHash = 1;
        while (currentOffset < endAddress) {
            byte b;
            while ((b = UNSAFE.getByte(currentOffset++)) != ';') {
                nameArray[nameArrayOffset++] = b;
                nameHash = 31 * nameHash + b;
            }
            while ((b = UNSAFE.getByte(currentOffset++)) != '\n') {
                tempArray[tempArrayOffset++] = b;
            }
            processLine(nameArray, nameArrayOffset, nameHash, tempArray, tempArrayOffset, table);
            nameArrayOffset = 0;
            tempArrayOffset = 0;
            nameHash = 1;
        }
        return table;

    }

    public static void main(String[] args) throws IOException {
        String filename = args.length > 0 ? args[0] : FILE_NAME;
        FileChannel fc = FileChannel.open(Paths.get(filename), StandardOpenOption.READ);
        final long fileSize = fc.size();
        final long startAddress = fc.map(FileChannel.MapMode.READ_ONLY, 0, fileSize, Arena.global()).address();
        final long endAddress = startAddress + fileSize;
        final long[][] segments = findSegments(startAddress, endAddress, fileSize, fileSize > 1024 * 1024 * 1024 ? 6 : 1);
        final List<Table> collect = Arrays.stream(segments).parallel().map(s -> readFile(s[0], s[1])).toList();
        Map<String, Row> finalMap = new TreeMap<>();
        for (final Table t : collect) {
            for (int j = 0; j < Table.TABLE_SIZE; j++) {
                final Row row = t.table[j];
                if (row == null) {
                    continue;
                }
                finalMap.compute(row.name, (_, v) -> v == null ? row : v.update(row));
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
