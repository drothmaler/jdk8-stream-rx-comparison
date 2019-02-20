/*
 * Copyright (C) 2015 José Paumard
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA  02111-1307, USA.
 */

package org.paumard.jdk8.rxjava;

import java.util.*;
import java.util.Map.Entry;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.openjdk.jmh.annotations.*;


import org.paumard.jdk8.bench.ShakespearePlaysScrabble;
import rx.Observable;
import rx.Single;
import rx.functions.Func1;


/**
 *
 * @author José
 */
public class ShakespearePlaysScrabbleWithRxJavaOptimized extends ShakespearePlaysScrabble {

    /*
    Result: 12,690 ±(99.9%) 0,148 s/op [Average]
              Statistics: (min, avg, max) = (12,281, 12,690, 12,784), stdev = 0,138
              Confidence interval (99.9%): [12,543, 12,838]
              Samples, N = 15
                    mean =     12,690 ±(99.9%) 0,148 s/op
                     min =     12,281 s/op
              p( 0,0000) =     12,281 s/op
              p(50,0000) =     12,717 s/op
              p(90,0000) =     12,784 s/op
              p(95,0000) =     12,784 s/op
              p(99,0000) =     12,784 s/op
              p(99,9000) =     12,784 s/op
              p(99,9900) =     12,784 s/op
              p(99,9990) =     12,784 s/op
              p(99,9999) =     12,784 s/op
                     max =     12,784 s/op


            # Run complete. Total time: 00:06:26

            Benchmark                                               Mode  Cnt   Score   Error  Units
            ShakespearePlaysScrabbleWithRxJava.measureThroughput  sample   15  12,690 ± 0,148   s/op

            Benchmark                                              Mode  Cnt       Score      Error  Units
            ShakespearePlaysScrabbleWithRxJava.measureThroughput   avgt   15  250074,776 ± 7736,734  us/op
            ShakespearePlaysScrabbleWithStreams.measureThroughput  avgt   15   29389,903 ± 1115,836  us/op

    */
    @Benchmark
    @BenchmarkMode(Mode.AverageTime)
    @OutputTimeUnit(TimeUnit.MILLISECONDS)
    @Warmup(
        iterations = 20
    )
    @Measurement(
        iterations = 20
    )
    @Fork(5)
    public List<Entry<Integer, List<String>>> measureThroughput() throws InterruptedException {

        // Function to compute the score of a given word
        Func1<Character, Integer> scoreOfALetter = letter -> letterScores[letter - 'a'];

        // score of the same letters in a word
        Func1<Entry<Character, AtomicLong>, Integer> letterScore =
                entry -> letterScores[entry.getKey() - 'a'] *
                        Integer.min(
                                (int) entry.getValue().get(),
                                (int) scrabbleAvailableLetters[entry.getKey() - 'a']

                        );

        // Histogram of the letters in a given word
        Func1<String, Observable<HashMap<Character, AtomicLong>>> histoOfLetters =
                word -> stringObservable(word)
                        .collect(
                                HashMap::new,
                                (HashMap<Character, AtomicLong> map, Character value) ->
                                {
                                    AtomicLong newValue = map.computeIfAbsent(value, k -> new AtomicLong(0L));
                                    newValue.incrementAndGet();
                                }

                        );

        // number of blanks for a given letter
        Func1<Entry<Character, AtomicLong>, Long> blank =
                entry ->
                        Long.max(
                                0L,
                                entry.getValue().get() -
                                        scrabbleAvailableLetters[entry.getKey() - 'a']

                        );

        // number of blanks for a given word
        Func1<String, Single<Long>> nBlanks =
                word -> histoOfLetters.call(word)
                        .flatMapIterable(HashMap::entrySet)
                        .map(blank)
                        .reduce(Long::sum)
                        .toSingle();


        // can a word be written with 2 blanks?
        Func1<String, Single<Boolean>> checkBlanks =
                word -> nBlanks.call(word)
                        .map(l -> l <= 2L);

        // score taking blanks into account letterScore1
        Func1<String, Observable<Integer>> score2 =
                word -> histoOfLetters.call(word)
                        .flatMapIterable(HashMap::entrySet)
                        .map(letterScore)
                        .reduce(Integer::sum);

        // Placing the word on the board
        // Building the streams of first and last letters
        Func1<String, Observable<Character>> first3 =
                word -> stringObservable(word).limit(3);
        Func1<String, Observable<Character>> last3 =
                word -> stringObservable(word).skip(3);


        // Stream to be maxed
        Func1<String, Observable<Character>> toBeMaxed =
                word -> Observable.concat(first3.call(word), last3.call(word));

        // Bonus for double letter
        Func1<String, Observable<Integer>> bonusForDoubleLetter =
                word -> toBeMaxed.call(word)
                        .map(scoreOfALetter)
                        .reduce(Integer::max);

        // score of the word put on the board
        Func1<String, Observable<Integer>> score3 =
                word ->
                        Observable.concat(
                                score2.call(word),
                                score2.call(word),
                                bonusForDoubleLetter.call(word),
                                bonusForDoubleLetter.call(word),
                                Observable.just(word.length() == 7 ? 50 : 0)
                        )
                                .reduce(Integer::sum);

        Func1<Func1<String, Observable<Integer>>, Observable<TreeMap<Integer, List<String>>>> buildHistoOnScore =
                score -> Observable.from(shakespeareWords)
                        .filter(scrabbleWords::contains)
                        .filter(word -> checkBlanks.call(word).toBlocking().value())
                        .collect(
                                () -> new TreeMap<>(Comparator.reverseOrder()),
                                (TreeMap<Integer, List<String>> map, String word) -> {
                                    Integer key = score.call(word).toBlocking().first();
                                    List<String> list = map.computeIfAbsent(key, k -> new ArrayList<>());
                                    list.add(word);
                                }
                        );

        // best key / value pairs
        List<Entry<Integer, List<String>>> finalList2 =
                buildHistoOnScore.call(score3)
                        .flatMapIterable(TreeMap::entrySet)
                        .take(3)
                        .collect(
                                () -> new ArrayList<Entry<Integer, List<String>>>(),
                                (list, entry) -> list.add(entry)
                        )
                        .toBlocking()
                        .first();


//        System.out.println(finalList2);

        return finalList2;
    }

    private Observable<Character> stringObservable(final String str) {
        return Observable.create(subscriber -> {
            try {
                for (char c : str.toCharArray()) {
                    subscriber.onNext(c);
                }

                if (!subscriber.isUnsubscribed()) {
                    subscriber.onCompleted();
                }
            } catch (Exception t) {
                if (!subscriber.isUnsubscribed()) {
                    subscriber.onError(t);
                }
            }
        });
    }
}
