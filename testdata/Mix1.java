import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class Mix1 {

    record Pair(String key, int value) {
    }

    static int classify(int x) {
        switch (x % 4) {
            case 0:
                return 10;
            case 1:
                return 20;
            case 2:
                return 30;
            default:
                return 40;
        }
    }

    public static void main(String[] args) {
        List<Pair> pairs = new ArrayList<>();
        pairs.add(new Pair("alpha", 1));
        pairs.add(new Pair("beta", 2));
        pairs.add(new Pair("gamma", 3));

        Map<String, Integer> index = new HashMap<>();
        for (Pair p : pairs) {
            index.put(p.key(), p.value());
        }

        System.out.println(pairs.size());
        System.out.println(index.size());
        System.out.println(index.get("beta"));
        System.out.println(index.containsKey("gamma"));

        int total = 0;
        for (Pair p : pairs) {
            total += classify(p.value());
        }
        System.out.println(total);

        Pair probe = new Pair("alpha", 1);
        System.out.println(pairs.get(0).equals(probe));
        System.out.println(pairs.get(0).equals(pairs.get(1)));
        System.out.println(pairs.get(0).toString());

        System.out.println("done");
    }
}