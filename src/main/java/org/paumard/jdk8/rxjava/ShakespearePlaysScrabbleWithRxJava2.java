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

import io.reactivex.Maybe;
import io.reactivex.Single;
import org.openjdk.jmh.annotations.*;
import org.paumard.jdk8.bench.ShakespearePlaysScrabble;
import org.paumard.jdk8.util.IterableSpliterator;
import io.reactivex.Observable;
import io.reactivex.functions.Function;

import java.util.*;
import java.util.Map.Entry;
import java.util.concurrent.TimeUnit;

/**
 *
 * @author José
 */
public class ShakespearePlaysScrabbleWithRxJava2 extends ShakespearePlaysScrabble {

    interface Wrapper<T> {
        T get() ;

        default Wrapper<T> set(T t) {
            return () -> t ;
        }
    }

    interface IntWrapper {
        int get() ;

        default IntWrapper set(int i) {
            return () -> i ;
        }

        default IntWrapper incAndSet() {
            return () -> get() + 1 ;
        }
    }

    interface LongWrapper {
        long get() ;

        default LongWrapper set(long l) {
            return () -> l ;
        }

        default LongWrapper incAndSet() {
            return () -> get() + 1L ;
        }

        default LongWrapper add(LongWrapper other) {
            return () -> get() + other.get() ;
        }
    }

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
        iterations=20
    )
    @Measurement(
        iterations=20
    )
    @Fork(5)
    public List<Entry<Integer, List<String>>> measureThroughput() throws InterruptedException {

        // Function to compute the score of a given word
        Function<Integer, Observable<Integer>> scoreOfALetter = letter -> Observable.just(letterScores[letter - 'a']) ;

        // score of the same letters in a word
        Function<Entry<Integer, LongWrapper>, Observable<Integer>> letterScore =
                entry ->
                    Observable.just(
                        letterScores[entry.getKey() - 'a']*
                        Integer.min(
                                (int)entry.getValue().get(),
                                scrabbleAvailableLetters[entry.getKey() - 'a']
                            )
                    ) ;

        Function<String, Observable<Integer>> toIntegerObservable =
                string -> Observable.fromIterable(IterableSpliterator.of(string.chars().boxed().spliterator())) ;

        // Histogram of the letters in a given word
        Function<String, Single<HashMap<Integer, LongWrapper>>> histoOfLetters =
                word -> toIntegerObservable.apply(word)
                            .collect(
                                HashMap::new,
                                (map, value) ->
                                    {
                                        LongWrapper newValue = map.get(value) ;
                                        if (newValue == null) {
                                            newValue = () -> 0L ;
                                        }
                                        map.put(value, newValue.incAndSet()) ;
                                    }

                            ) ;

        // number of blanks for a given letter
        Function<Entry<Integer, LongWrapper>, Observable<Long>> blank =
                entry ->
                    Observable.just(
                        Long.max(
                            0L,
                            entry.getValue().get() -
                            scrabbleAvailableLetters[entry.getKey() - 'a']
                        )
                    ) ;

        // number of blanks for a given word
        Function<String, Maybe<Long>> nBlanks =
                word -> histoOfLetters.apply(word)
                            .flatMapObservable(map -> Observable.fromIterable(() -> map.entrySet().iterator()))
                            .flatMap(blank)
                            .reduce(Long::sum);


        // can a word be written with 2 blanks?
        Function<String, Observable<Boolean>> checkBlanks =
                word -> nBlanks.apply(word)
                            .flatMapObservable(l -> Observable.just(l <= 2L)) ;

        // score taking blanks into account letterScore1
        Function<String, Maybe<Integer>> score2 =
                word -> histoOfLetters.apply(word)
                            .flatMapObservable(map -> Observable.fromIterable(() -> map.entrySet().iterator()))
                            .flatMap(letterScore)
                            .reduce(Integer::sum) ;

        // Placing the word on the board
        // Building the streams of first and last letters
        Function<String, Observable<Integer>> first3 =
                word -> Observable.fromIterable(IterableSpliterator.of(word.chars().boxed().limit(3).spliterator())) ;
        Function<String, Observable<Integer>> last3 =
                word -> Observable.fromIterable(IterableSpliterator.of(word.chars().boxed().skip(3).spliterator())) ;


        // Stream to be maxed
        Function<String, Observable<Integer>> toBeMaxed =
            word -> Observable.just(first3.apply(word), last3.apply(word))
                        .flatMap(observable -> observable) ;

        // Bonus for double letter
        Function<String, Maybe<Integer>> bonusForDoubleLetter =
            word -> toBeMaxed.apply(word)
                        .flatMap(scoreOfALetter)
                        .reduce(Integer::max) ;

        // score of the word put on the board
        Function<String, Maybe<Integer>> score3 =
            word ->
                Observable.just(
                        score2.apply(word),
                        score2.apply(word),
                        bonusForDoubleLetter.apply(word),
                        bonusForDoubleLetter.apply(word),
                        Observable.just(word.length() == 7 ? 50 : 0)
                )
                .flatMap(observable -> observable)
                .reduce(Integer::sum) ;

        Function<Function<String, Maybe<Integer>>, Single<TreeMap<Integer, List<String>>>> buildHistoOnScore =
                score -> Observable.fromIterable(() -> shakespeareWords.iterator())
                                .filter(scrabbleWords::contains)
                                .filter(word -> checkBlanks.apply(word).blockingFirst())
                                .collect(
                                    TreeMap::new,
                                    (TreeMap<Integer, List<String>> map, String word) -> {
                                        Integer key = score.apply(word).blockingGet();
                                        List<String> list = map.computeIfAbsent(key, k -> new ArrayList<>());
                                        list.add(word) ;
                                    }
                                ) ;

        // best key / value pairs
        List<Entry<Integer, List<String>>> finalList2 =
                buildHistoOnScore.apply(score3)
                    .flatMapObservable(map -> Observable.fromIterable(() -> map.entrySet().iterator()))
                    .take(3)
                    .collect(ArrayList::new, ArrayList::add)
                    .toBlocking()
                    .first() ;


//        System.out.println(finalList2);

        return finalList2 ;
    }
}
