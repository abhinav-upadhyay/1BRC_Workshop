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

import java.io.File;
import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.reflect.Field;
import java.nio.channels.FileChannel;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;

public class CalculateAverage_PEWorkshop3 {

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
    };

    private static final Map<String, Row> records = new HashMap<>();

    private static final long SEMICOLON_PATTERN = compilePattern((byte) ';');

    private static long compilePattern(byte byteToFind) {
        long pattern = byteToFind & 0xFFL;
        return pattern
                | (pattern << 8)
                | (pattern << 16)
                | (pattern << 24)
                | (pattern << 32)
                | (pattern << 40)
                | (pattern << 48)
                | (pattern << 56);
    }

    private static int firstInstance(long word, long pattern) {
        long input = word ^ pattern;
        long tmp = (input & 0x7F7F7F7F7F7F7F7FL) + 0x7F7F7F7F7F7F7F7FL;
        tmp = ~(tmp | input | 0x7F7F7F7F7F7F7F7FL);
        return Long.numberOfLeadingZeros(tmp) >>> 3;
    }

    public static void longToBytes(long l, byte[] barray, int offset) {
        for (int i = Long.BYTES - 1; i >= 0; i--) {
            barray[i + offset] = (byte) (l & 0xFF);
            l >>= Byte.SIZE;
        }
    }

    private static void readFile2(long startAddress, long endAddress) {
        long currentOffset = startAddress;
        byte[] barray = new byte[512];
        int barrayOffset = 0;
        while (currentOffset < endAddress) {
            long word = Long.reverseBytes(UNSAFE.getLong(currentOffset));
            final int semiColonIndex = firstInstance(word, SEMICOLON_PATTERN);
            currentOffset += semiColonIndex;
            longToBytes(word, barray, barrayOffset);
            barrayOffset += semiColonIndex;
            if (semiColonIndex != Long.BYTES) {
                // we found semicolon, let's find newline
                byte b;
                byte[] temperatureBytes = new byte[8];
                int tBytesOffset = 0;
                currentOffset++;
                while ((b = UNSAFE.getByte(currentOffset++)) != '\n') {
                    temperatureBytes[tBytesOffset++] = b;
                }
                float temperature = Float.parseFloat(new String(temperatureBytes, 0, tBytesOffset));
                String name = new String(barray, 0, barrayOffset);
                records.compute(name, (_, v) -> v == null ? Row.create(temperature) : v.update(temperature));
                barrayOffset = 0;
            }
        }
    }

    public static void main(String[] args) throws IOException {
        String filename = args.length > 0 ? args[0] : FILE_NAME;
        File f = new File(filename);
        final long fileSize = f.length();
        // final MemorySegment segment = FileChannel.open(Paths.get(filename), StandardOpenOption.READ).map(FileChannel.MapMode.READ_ONLY, 0, fileSize, Arena.global());
        final long startAddress = FileChannel.open(Paths.get(filename), StandardOpenOption.READ).map(FileChannel.MapMode.READ_ONLY, 0, fileSize, Arena.global())
                .address();
        long endAddress = startAddress + fileSize;
        readFile2(startAddress, endAddress);
        System.out.println(new TreeMap<>(records));
    }
}
