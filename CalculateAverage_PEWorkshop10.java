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

public class CalculateAverage_PEWorkshop10 {

    /**
     *  Use SWAR to find semi colons 8 bytes at a time.
     *
     *  This makes hashing of the location name difficult because hashing longs doesn't lead
     *  to as good hash values as hashing individual bytes
     *
     *  I am not an expert on hashing. Taking Cliff Click's hash functions to hash the long values
     *  Also, reusing his reprobing strategy and reducing the hash table size to just 16k
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

    public static final long HASSEMI = 0x3B3B3B3B3B3B3B3BL;

    static long has0(long x) {
        return (x - 0x0101010101010101L) & (~x) & 0x8080808080808080L;
    }

    private static class Table {
        private static final int TABLE_SIZE = 0x4000; // collisions with table smaller than this. Need a better hash function
        private static final int TABLE_MASK = TABLE_SIZE - 1;
        Row[] table = new Row[TABLE_SIZE];

        public void put(long cityStartOffset, long nameHash, int temperature) {
            final int uhash = (int) uhash(nameHash);
            int index = hash_hash(uhash); // dropped the last 4 bytes
            Row row = table[index];
            if (row == null) {
                byte b;
                byte[] array = new byte[256];
                int i = 0;
                while ((b = UNSAFE.getByte(cityStartOffset++)) != ';') {
                    array[i++] = b;
                }
                table[index] = Row.create(new String(array, 0, i), temperature, uhash);
                return;
            }

            while (row.hash != uhash) {
                index = reprobe(index, uhash);
                row = table[index];
                if (row == null) {
                    byte b;
                    byte[] array = new byte[256];
                    int i = 0;
                    while ((b = UNSAFE.getByte(cityStartOffset++)) != ';') {
                        array[i++] = b;
                    }
                    table[index] = Row.create(new String(array, 0, i), temperature, uhash);
                    return;
                }
            }
            row.update(temperature);
        }

        private static long uhash(long n8) {
            return n8 ^ (n8 >> 29);
        }

        private static int hash_hash(int uhash) {
            // Index in small table
            int ihash = uhash;
            ihash = ihash ^ (ihash >> 17);
            ihash = ihash + 29 * uhash;
            ihash &= TABLE_MASK;
            return ihash;
        }

        private static int reprobe(int ihash, int uhash) {
            // return (ihash + (uhash | 1)) & TABLE_MASK;
            return (ihash + (uhash)) & TABLE_MASK;
        }

    }

    private static final class Row {
        private final String name;
        private int minTemp;
        private int maxTemp;
        private int count;
        private int sum;

        private final int hash;

        private Row(String name, int minTemp, int maxTemp, int count, int sum, int hash) {
            this.name = name;
            this.minTemp = minTemp;
            this.maxTemp = maxTemp;
            this.count = count;
            this.sum = sum;
            this.hash = hash;
        }

        void update(int temperature) {
            this.minTemp = Integer.min(this.minTemp, temperature);
            this.maxTemp = Integer.max(this.maxTemp, temperature);
            this.count++;
            this.sum += temperature;
        }

        public static Row create(String name, int temperature, int hash) {
            return new Row(name, temperature, temperature, 1, temperature, hash);
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

    private static void processLine(long cityStartOffset, long nameHash, int temperature, Table table) {
        table.put(cityStartOffset, nameHash, temperature);
    }

    private static Table readFile(long startAddress, long endAddress) {
        Table table = new Table();
        long currentOffset = startAddress;
        long nameHash = 1;
        while (currentOffset < endAddress) {
            long cityStart = currentOffset;
            long word = UNSAFE.getLong(currentOffset);
            long hasSemi = has0(word ^ HASSEMI);
            while (hasSemi == 0) {
                currentOffset += 8;
                nameHash ^= word;
                word = UNSAFE.getLong(currentOffset);
                hasSemi = has0(word ^ HASSEMI);
            }
            int trailingZeros = Long.numberOfTrailingZeros(hasSemi);
            int semiColonIndex = trailingZeros >> 3;
            if (trailingZeros >= 8) {
                int nonZeroBits = 64 - trailingZeros;
                nameHash ^= ((word << nonZeroBits) >> nonZeroBits);
                currentOffset += semiColonIndex;
            }
            currentOffset++; // skip ;
            int temperature = 0;
            int scale = 1;
            int isNegative = 1;
            byte b = UNSAFE.getByte(currentOffset++);
            if (b == '-') {
                isNegative = -1;
            }
            else {
                temperature = b - '0';
                scale = 10;
            }
            while ((b = UNSAFE.getByte(currentOffset++)) != '\n') {
                if (b == '.') {
                    continue;
                }
                temperature = temperature * scale + b - '0';
                scale = 10;
            }
            temperature *= isNegative;
            processLine(cityStart, nameHash, temperature, table);
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
