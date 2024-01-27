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

import java.io.*;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.reflect.Field;
import java.nio.channels.FileChannel;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

public class CalculateAverage_PEWorkshop2 {

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

    private static void processLine(byte[] barray, int size, int semiColonIndex) {
        String name = new String(barray, 0, semiColonIndex);
        String temperatureStr = new String(barray, semiColonIndex + 1, size - semiColonIndex - 1);
        float temperature = Float.parseFloat(temperatureStr);
        records.compute(name, (k, v) -> v == null ? Row.create(temperature) : v.update(temperature));
    }

    private static void readFile(long startAddress, long endAddress) {
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
            processLine(barray, barrayOffset, semiColonIndex);
            barrayOffset = 0;
        }

    }

    public static void main(String[] args) throws IOException {
        String filename = args.length > 0 ? args[0] : FILE_NAME;
        File f = new File(filename);
        final long fileSize = f.length();
        final long startAddress = FileChannel.open(Paths.get(filename), StandardOpenOption.READ).map(FileChannel.MapMode.READ_ONLY, 0, fileSize, Arena.global())
                .address();
        final long endAddress = startAddress + fileSize;
        readFile(startAddress, endAddress);
        System.out.println(new TreeMap<>(records));
    }
}
