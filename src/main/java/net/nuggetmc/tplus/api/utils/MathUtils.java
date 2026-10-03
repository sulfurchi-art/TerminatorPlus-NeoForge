package net.nuggetmc.tplus.api.utils;

import net.minecraft.world.phys.Vec3;
import net.nuggetmc.tplus.api.Terminator;

import java.text.DecimalFormat;
import java.util.*;
import java.util.stream.Collectors;

public class MathUtils {

    public static final Random RANDOM = new Random();
    public static final DecimalFormat FORMATTER_1 = new DecimalFormat("0.#");
    public static final DecimalFormat FORMATTER_2 = new DecimalFormat("0.##");

    public static float[] fetchYawPitch(Vec3 dir) {
        double x = dir.x;
        double z = dir.z;

        float[] out = new float[2];

        if (x == 0.0D && z == 0.0D) {
            out[1] = (float) (dir.y > 0.0D ? -90 : 90);
        } else {
            double theta = Math.atan2(-x, z);
            out[0] = (float) Math.toDegrees((theta + 6.283185307179586D) % 6.283185307179586D);

            double x2 = square(x);
            double z2 = square(z);
            double xz = Math.sqrt(x2 + z2);
            out[1] = (float) Math.toDegrees(Math.atan(-dir.y / xz));
        }

        return out;
    }

    public static float fetchPitch(Vec3 dir) {
        double x = dir.x;
        double z = dir.z;

        float result;

        if (x == 0.0D && z == 0.0D) {
            result = (float) (dir.y > 0.0D ? -90 : 90);
        } else {
            double x2 = square(x);
            double z2 = square(z);
            double xz = Math.sqrt(x2 + z2);
            result = (float) Math.toDegrees(Math.atan(-dir.y / xz));
        }

        return result;
    }

    public static Vec3 circleOffset(double r) {
        double rad = 2 * Math.random() * Math.PI;

        double x = r * Math.random() * Math.cos(rad);
        double z = r * Math.random() * Math.sin(rad);

        return new Vec3(x, 0, z);
    }

    public static boolean isNotFinite(Vec3 vector) {
        return !Double.isFinite(vector.x) || !Double.isFinite(vector.y) || !Double.isFinite(vector.z);
    }

    /**
     * Replaces every non-finite component with 0.
     */
    public static Vec3 clean(Vec3 vector) {
        return new Vec3(
                Double.isFinite(vector.x) ? vector.x : 0,
                Double.isFinite(vector.y) ? vector.y : 0,
                Double.isFinite(vector.z) ? vector.z : 0
        );
    }

    /**
     * Same rotation as Bukkit's {@code Vector#rotateAroundY}, in full double precision
     * (vanilla's {@link Vec3#yRot(float)} goes through the float sine table).
     */
    public static Vec3 rotateAroundY(Vec3 vector, double angle) {
        double angleCos = Math.cos(angle);
        double angleSin = Math.sin(angle);

        double x = angleCos * vector.x + angleSin * vector.z;
        double z = -angleSin * vector.x + angleCos * vector.z;

        return new Vec3(x, vector.y, z);
    }

    public static Vec3 withY(Vec3 vector, double y) {
        return new Vec3(vector.x, y, vector.z);
    }

    public static <E> E getRandomSetElement(Set<E> set) {
        return set.isEmpty() ? null : set.stream().skip(RANDOM.nextInt(set.size())).findFirst().orElse(null);
    }

    public static double square(double n) {
        return n * n;
    }

    public static String round1Dec(double n) {
        return FORMATTER_1.format(n);
    }

    public static String round2Dec(double n) {
        return FORMATTER_2.format(n);
    }

    public static List<Map.Entry<Terminator, Integer>> sortByValue(HashMap<Terminator, Integer> hm) {
        List<Map.Entry<Terminator, Integer>> list = new LinkedList<>(hm.entrySet());
        list.sort(Map.Entry.comparingByValue());
        Collections.reverse(list);
        return list;
    }

    public static double generateConnectionValue(List<Double> list, double mutationSize) {
        double[] bounds = getBounds(list, mutationSize);
        return random(bounds[0], bounds[1]);
    }

    public static double generateConnectionValue(List<Double> list) {
        return generateConnectionValue(list, 0);
    }

    public static double random(double low, double high) {
        return Math.random() * (high - low) + low;
    }

    public static double sum(List<Double> list) {
        return list.stream().mapToDouble(n -> n).sum();
    }

    public static double min(List<Double> list) {
        if (list.isEmpty()) {
            return 0;
        }

        double min = Double.MAX_VALUE;

        for (double n : list) {
            if (n < min) {
                min = n;
            }
        }

        return min;
    }

    public static double max(List<Double> list) {
        if (list.isEmpty()) {
            return 0;
        }

        double max = 0;

        for (double n : list) {
            if (n > max) {
                max = n;
            }
        }

        return max;
    }

    public static double getMidValue(List<Double> list) {
        return (min(list) + max(list)) / 2D;
    }

    public static double distribution(List<Double> list, double mid) {
        return Math.sqrt(sum(list.stream().map(n -> Math.pow(n - mid, 2)).collect(Collectors.toList())) / list.size());
    }

    public static double[] getBounds(List<Double> list, double mutationSize) {
        double mid = getMidValue(list);
        double dist = distribution(list, mid);
        double p = mutationSize * dist / Math.sqrt(list.size());

        return new double[]{
                mid - p,
                mid + p
        };
    }

    public static double getMutationSize(int generation) {
        int shift = 4;

        if (generation <= shift + 1) {
            return 7.38905609893;
        }

        double a = 0.8;
        double b = -8.5 - shift;
        double c = 2;

        return Math.pow(a, generation + b) + c;
    }
}
