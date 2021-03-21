package inside.util;

import reactor.util.annotation.Nullable;

import java.nio.charset.*;
import java.util.function.Function;

public abstract class Strings{
    public static final int DEFAULT_LEVENSHTEIN_DST = 3;

    public static final Charset utf8 = StandardCharsets.UTF_8;

    private Strings(){

    }

    public static boolean isEmpty(@Nullable CharSequence cs){
        return cs == null || cs.length() == 0;
    }

    public static int parseInt(String s){
        return parseInt(s, Integer.MIN_VALUE);
    }

    public static int parseInt(String s, int defaultValue){
        try{
            return Integer.parseInt(s);
        }catch(Exception e){
            return defaultValue;
        }
    }

    public static long parseLong(String s){
        return parseLong(s, Long.MIN_VALUE);
    }

    public static long parseLong(String s, long defaultValue){
        return parseLong(s, 10, defaultValue);
    }

    public static long parseLong(@Nullable String s, int radix, long defaultValue){
        if(s == null){
            return defaultValue;
        }
        return parseLong(s, radix, 0, s.length(), defaultValue);
    }

    public static long parseLong(@Nullable String s, int radix, int start, int end, long defaultValue){
        if(s == null){
            return defaultValue;
        }

        boolean negative = false;
        int i = start, len = end - start;
        long limit = -9223372036854775807L;
        if(len <= 0){
            return defaultValue;
        }else{
            char firstChar = s.charAt(i);
            if(firstChar < '0'){
                if(firstChar == '-'){
                    negative = true;
                    limit = -9223372036854775808L;
                }else if(firstChar != '+'){
                    return defaultValue;
                }

                if(len == 1) return defaultValue;

                ++i;
            }

            long result;
            int digit;
            for(result = 0L; i < end; result -= digit){
                digit = Character.digit(s.charAt(i++), radix);
                if(digit < 0){
                    return defaultValue;
                }

                result *= radix;
                if(result < limit + (long)digit){
                    return defaultValue;
                }
            }

            return negative ? result : -result;
        }
    }

    public static int levenshtein(String x, String y){
        int[][] dp = new int[x.length() + 1][y.length() + 1];

        for(int i = 0; i <= x.length(); i++){
            for(int j = 0; j <= y.length(); j++){
                if(i == 0){
                    dp[i][j] = j;
                }else if(j == 0){
                    dp[i][j] = i;
                }else{
                    dp[i][j] = Math.min(Math.min(dp[i - 1][j - 1] + (x.charAt(i - 1) == y.charAt(j - 1) ? 0 : 1),
                            dp[i - 1][j] + 1),
                            dp[i][j - 1] + 1);
                }
            }
        }

        return dp[x.length()][y.length()];
    }

    /* TODO: Change #findClosest generics from String to CharSequence for better compatibility */

    @Nullable
    public static <T> T findClosest(Iterable<? extends T> all, Function<T, String> comp, String wrong){
        return findClosest(all, comp, wrong, DEFAULT_LEVENSHTEIN_DST);
    }

    @Nullable
    public static <T> T findClosest(Iterable<? extends T> all, Function<T, String> comp, String wrong, int max){
        int min = 0;
        T closest = null;

        for(T t : all){
            int dst = levenshtein(comp.apply(t), wrong);
            if(dst < max && (closest == null || dst < min)){
                min = dst;
                closest = t;
            }
        }

        return closest;
    }
}
