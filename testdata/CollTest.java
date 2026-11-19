import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class CollTest {

    public static void main(String[] args) {
        List<String> list = new ArrayList<String>();
        list.add("alpha");
        list.add("beta");
        list.add("gamma");
        System.out.println(list.size());
        System.out.println(list.get(0));
        System.out.println(list.get(2));
        System.out.println(list.contains("beta") ? 1 : 0);
        System.out.println(list.contains("delta") ? 1 : 0);
        System.out.println(list.indexOf("gamma"));
        System.out.println(list.isEmpty() ? 1 : 0);

        list.set(1, "BETA");
        System.out.println(list.get(1));
        System.out.println(list.remove(0));

        Map<String, Integer> map = new HashMap<String, Integer>();
        map.put("one", 11);
        map.put("two", 22);
        map.put("three", 33);
        System.out.println(map.size());
        System.out.println(map.get("one"));
        System.out.println(map.get("two"));
        System.out.println(map.containsKey("three") ? 1 : 0);
        System.out.println(map.containsKey("four") ? 1 : 0);
        System.out.println(map.isEmpty() ? 1 : 0);

        map.put("two", 222);
        System.out.println(map.get("two"));
        System.out.println(map.remove("one"));

        int[] nums = new int[] { 5, 3, 9, 1 };
        Arrays.sort(nums);
        System.out.println(nums[0]);
        System.out.println(nums[1]);
        System.out.println(nums[2]);
        System.out.println(nums[3]);

        int[] copy = new int[4];
        System.arraycopy(nums, 0, copy, 0, 4);
        System.out.println(copy[0]);
        System.out.println(copy[3]);
        System.out.println(Arrays.equals(nums, copy) ? 1 : 0);

        List<Integer> ints = new ArrayList<Integer>();
        int i = 0;
        while (i < 5) {
            ints.add(i * 3);
            i = i + 1;
        }
        int sum = 0;
        i = 0;
        while (i < ints.size()) {
            sum = sum + ints.get(i);
            i = i + 1;
        }
        System.out.println(sum);
    }
}