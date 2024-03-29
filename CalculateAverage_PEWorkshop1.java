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

import java.io.BufferedReader;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.util.*;

public class CalculateAverage_PEWorkshop1 {

    /**
     * Most naive implementation. Simply streaming lines using BuffredReader and parsing them
     */
    private static final String FILE_NAME = "./measurements.txt";

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

    public static void main(String[] args) throws FileNotFoundException {
        String filename = args.length > 0 ? args[0] : FILE_NAME;
        BufferedReader br = new BufferedReader(new FileReader(filename));
        br.lines().forEach(l -> {
            int semiColonIndex = l.indexOf(";");
            String locationName = l.substring(0, semiColonIndex);
            String temperatureValStr = l.substring(semiColonIndex + 1);
            float temperature = Float.parseFloat(temperatureValStr);
            records.compute(locationName, (k, v) -> v == null ? Row.create(temperature) : v.update(temperature));
        });
        System.out.println(new TreeMap<>(records));
    }
}
